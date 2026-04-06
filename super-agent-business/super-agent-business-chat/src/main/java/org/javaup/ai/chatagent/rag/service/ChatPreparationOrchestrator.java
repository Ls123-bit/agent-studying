package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.memory.ConversationMemoryContext;
import org.javaup.ai.chatagent.model.memory.ConversationSummaryPayload;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.model.HistoryPlanningContext;
import org.javaup.ai.chatagent.rag.model.RagRewriteResult;
import org.javaup.ai.chatagent.service.ConversationMemoryService;
import org.javaup.ai.chatagent.support.TimeSensitiveQueryHelper;
import org.javaup.enums.ChatQueryMode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 聊天前置编排器。
 *
 * <p>这层不负责真正生成回答，
 * 它的职责是尽可能在模型开始输出之前，把“本轮应该怎么处理”规划清楚：</p>
 * <p>1. 读取会话记忆。</p>
 * <p>2. 根据前端显式模式决定主链路。</p>
 * <p>3. 在文档问答模式下做问题改写与子问题拆分。</p>
 * <p>4. 产出最终执行计划。</p>
 */
@Service
public class ChatPreparationOrchestrator {

    private static final Set<String> CAPABILITY_HINTS = Set.of(
        "你都能干什么", "你能做什么", "你可以做什么", "你会什么", "你是谁", "怎么用你", "你能帮我什么"
    );

    private static final Set<String> OPEN_CHAT_HINTS = Set.of(
        "天气", "温度", "下雨", "新闻", "股价", "汇率", "热搜", "今天", "明天", "最新", "现在"
    );

    private static final Set<String> CHITCHAT_HINTS = Set.of(
        "你好", "您好", "hello", "hi", "谢谢", "感谢", "再见", "拜拜"
    );

    private final ChatRagProperties properties;
    private final ChatQueryRewriteService chatQueryRewriteService;
    private final ConversationMemoryService conversationMemoryService;

    public ChatPreparationOrchestrator(ChatRagProperties properties,
                                       ChatQueryRewriteService chatQueryRewriteService,
                                       ConversationMemoryService conversationMemoryService) {
        this.properties = properties;
        this.chatQueryRewriteService = chatQueryRewriteService;
        this.conversationMemoryService = conversationMemoryService;
    }

    /**
     * 生成当前这轮对话的执行计划。
     */
    public ConversationExecutionPlan prepare(String conversationId,
                                             String question,
                                             ChatQueryMode chatMode,
                                             Long selectedDocumentId,
                                             Long selectedTaskId,
                                             LocalDate currentDate,
                                             String currentDateText) {
        /*
         * 读取长期摘要快照，并在必要时同步做增量压缩。
         */
        ConversationMemoryContext memoryContext = summarizeHistory(conversationId);
        /*
         * 这里故意把历史上下文拆成两层：
         * 1. historyPlanningContext：结构化要点，适合做改写和检索提示补全；
         * 2. historySummary：压缩后的最终文本，继续兼容当前依赖字符串上下文的组件。
         *
         * 这样我们不是简单地“把长期摘要再拼成一段大文本”，
         * 而是先把会话目标、已确认事实、待跟进问题、检索提示拆出来，
         * 再按当前链路需要组装成较短、噪音更低的 planning history。
         */
        HistoryPlanningContext historyPlanningContext = buildHistoryPlanningContext(memoryContext);
        String historySummary = buildPlanningHistory(memoryContext, historyPlanningContext);
        /*
         * 这两个布尔量不是给当前方法自己用的，而是给后续执行计划做“能力开关”：
         * - requiresCurrentDateAnchoring: 后面是否要把“今天/本周/今年”解释成当前日期
         * - requiresFreshSearch: 开放式提问时是否要优先做最新事实核实
         */
        boolean requiresCurrentDateAnchoring = TimeSensitiveQueryHelper.requiresCurrentDateAnchoring(question);
        boolean requiresFreshSearch = TimeSensitiveQueryHelper.requiresFreshSearch(question);
        if (chatMode == null) {
            throw new IllegalArgumentException("chatMode 不能为空");
        }

        /*
         * 开放式提问模式的目标就是“明确不走文档知识库”。
         * 因此这里不再让任何自动分流逻辑参与决策，
         * 直接产出 ReactAgent 计划。
         */
        if (chatMode == ChatQueryMode.OPEN_CHAT) {
            return basePlan(question, chatMode, memoryContext, historyPlanningContext, historySummary, currentDate, currentDateText,
                requiresCurrentDateAnchoring, requiresFreshSearch)
                .mode(ExecutionMode.REACT_AGENT)
                .build();
        }

        /*
         * 文档问答模式下，如果 RAG 总开关被关闭，就直接显式报错。
         * 这里不再退化成 OPEN_CHAT，
         * 因为那会破坏“用户已经明确选择文档问答”的边界承诺。
         */
        if (!properties.isEnabled()) {
            throw new IllegalStateException("当前文档问答模式未启用，请先开启聊天侧 RAG 编排");
        }
        if (selectedDocumentId == null || selectedTaskId == null) {
            throw new IllegalArgumentException("当前文档问答模式缺少有效的文档范围");
        }

        /* 
         * 文档问答模式下只保留“对文档内部检索真正有价值”的步骤：
         * 1. 会话记忆加载
         * 2. 查询改写 / 子问题拆分
         * 3. 固定文档范围的 RAG 回答
         */
        RagRewriteResult rewriteResult = chatQueryRewriteService.rewrite(question, historySummary);
        return basePlan(question, chatMode, memoryContext, historyPlanningContext, historySummary, currentDate, currentDateText,
            requiresCurrentDateAnchoring, requiresFreshSearch)
            .mode(ExecutionMode.RAG_CHAT)
            .rewrittenQuestion(rewriteResult.getRewrittenQuestion())
            .subQuestions(rewriteResult.getSubQuestions())
            .selectedDocumentId(selectedDocumentId)
            .selectedTaskId(selectedTaskId)
            /*
             * 当前文档问答模式下的无证据提示，不应该总是机械地说“证据不足”。
             * 对明显像“助手能力 / 天气 / 闲聊”的问题，更合适的解释是：
             * 你当前用的是文档模式，这类问题请切到开放式提问。
             *
             * 这里做的是一个非常窄的识别，只影响最终兜底文案，
             * 不参与主链路路由，也不会重新引回旧版自动分流体系。
             */
            .noEvidenceReply(buildDocumentModeNoEvidenceReply(question, requiresFreshSearch))
            .build();
    }

    /**
     * 构造所有模式都会复用的基础计划部分。
     */
    private ConversationExecutionPlan.ConversationExecutionPlanBuilder basePlan(String question,
                                                                                ChatQueryMode chatMode,
                                                                                ConversationMemoryContext memoryContext,
                                                                                HistoryPlanningContext historyPlanningContext,
                                                                                String historySummary,
                                                                                LocalDate currentDate,
                                                                                String currentDateText,
                                                                                boolean requiresCurrentDateAnchoring,
                                                                                boolean requiresFreshSearch) {
        /*
         * 这里先把所有执行模式都会共用的字段放进去，
         * 后面的 OPEN_CHAT / RAG_CHAT 再在这个 builder 基础上补充分支特有字段。
         *
         * 这样做的好处是：
         * 1. plan 的公共字段只维护一处
         * 2. 不同分支返回的对象结构更统一
         */
        return ConversationExecutionPlan.builder()
            .chatMode(chatMode)
            .originalQuestion(question)
            .agentQuestion(question)
            .rewrittenQuestion(question)
            .historySummary(historySummary)
            .longTermSummary(memoryContext.getLongTermSummary())
            .historyPlanningContext(historyPlanningContext)
            .recentHistoryTranscript(memoryContext.getRecentTranscript())
            .answerRecentTranscript(memoryContext.getAnswerRecentTranscript())
            .historyCompressionApplied(memoryContext.isCompressionApplied())
            .historyCoveredExchangeId(memoryContext.getCoveredExchangeId())
            .historyCoveredExchangeCount(memoryContext.getCoveredExchangeCount())
            .historyCompressionCount(memoryContext.getCompressionCount())
            .currentDate(currentDate)
            .currentDateText(currentDateText)
            .requiresCurrentDateAnchoring(requiresCurrentDateAnchoring)
            .requiresFreshSearch(requiresFreshSearch)
            .noEvidenceReply(properties.getNoEvidenceReply());
    }

    /**
     * 历史摘要只保留最近 N 轮，避免编排上下文无限增长。
     */
    private ConversationMemoryContext summarizeHistory(String conversationId) {
        /*
         * 这里正式把“历史压缩”的职责下沉到 ConversationMemoryService：
         * - 长期历史由持久化摘要快照承接
         * - 最近几轮继续保留原文窗口
         * - 如果后台预热还没完成，这里会同步自愈
         *
         * 编排器自己只消费最终的上下文结果，不再关心底层压缩细节。
         */
        return conversationMemoryService.loadMemoryContext(conversationId);
    }

    private HistoryPlanningContext buildHistoryPlanningContext(ConversationMemoryContext memoryContext) {
        ConversationSummaryPayload payload = memoryContext == null ? null : memoryContext.getSummaryPayload();
        if (payload == null) {
            return HistoryPlanningContext.builder().build();
        }
        /*
         * 这里只挑“对当前轮仍然有决策价值”的字段往编排层透传，
         * 不把整个 summaryPayload 原样塞给后续所有组件。
         *
         * 这样能避免两个问题：
         * 1. 下游每个组件都要自己理解完整 payload 结构，耦合面会变大；
         * 2. 历史信息过多时，问题改写和检索规划又退化回“吃一整坨历史文本”。
         */
        return HistoryPlanningContext.builder()
            .conversationGoal(payload.getConversationGoal())
            .stableFacts(payload.getStableFacts() == null ? List.of() : new ArrayList<>(payload.getStableFacts()))
            .pendingQuestions(payload.getPendingQuestions() == null ? List.of() : new ArrayList<>(payload.getPendingQuestions()))
            .retrievalHints(payload.getRetrievalHints() == null ? List.of() : new ArrayList<>(payload.getRetrievalHints()))
            .queryContextHints(payload.getRetrievalHints() == null ? List.of() : new ArrayList<>(payload.getRetrievalHints()))
            .build();
    }

    private String buildPlanningHistory(ConversationMemoryContext memoryContext,
                                        HistoryPlanningContext historyPlanningContext) {
        String structuredHistory = buildStructuredPlanningHistory(historyPlanningContext);
        String recentTranscript = memoryContext == null ? "" : safeText(memoryContext.getRecentTranscript());
        int maxChars = Math.max(1, properties.getPlanningHistoryMaxChars());
        if (recentTranscript.isBlank()) {
            return clipHead(structuredHistory, maxChars);
        }
        int recentBudget = Math.min(Math.max(maxChars / 2, (int) Math.round(maxChars * 0.65D)), maxChars);
        String recentPart = clipTail(recentTranscript, recentBudget);
        int structuredBudget = Math.max(0, maxChars - recentPart.length() - (recentPart.isBlank() ? 0 : 2));
        String structuredPart = clipHead(structuredHistory, structuredBudget);
        return joinNonBlank(structuredPart, recentPart);
    }

    private String buildStructuredPlanningHistory(HistoryPlanningContext historyPlanningContext) {
        StringBuilder builder = new StringBuilder();
        if (historyPlanningContext == null) {
            return "";
        }
        /*
         * 这里的顺序不是随便排的：
         * - 会话目标：先告诉编排器“这条会话长期在解决什么问题”
         * - 已确认事实：减少后续改写时把历史事实重新判成未知
         * - 待跟进问题：帮助识别当前追问是否在承接旧话题
         * - 检索提示：专门服务检索阶段的系统名、模块名、关键词继承
         */
        appendSection(builder, "会话目标", historyPlanningContext.getConversationGoal());
        appendBulletSection(builder, "已确认事实", historyPlanningContext.getStableFacts());
        appendBulletSection(builder, "待跟进问题", historyPlanningContext.getPendingQuestions());
        appendBulletSection(builder, "检索提示", historyPlanningContext.getRetrievalHints());
        return builder.toString().trim();
    }

    private void appendSection(StringBuilder builder, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append("【").append(title).append("】\n").append(content.trim()).append('\n');
    }

    private void appendBulletSection(StringBuilder builder, String title, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append("【").append(title).append("】\n");
        values.stream()
            .filter(item -> item != null && !item.isBlank())
            .limit(5)
            .forEach(item -> builder.append("- ").append(item.trim()).append('\n'));
    }

    private String clipHead(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        if (maxChars <= 1) {
            return "";
        }
        return normalized.substring(0, maxChars - 1) + "…";
    }

    private String clipTail(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        if (maxChars <= 1) {
            return "";
        }
        int start = Math.max(0, normalized.length() - (maxChars - 1));
        return "…" + normalized.substring(start);
    }

    private String joinNonBlank(String left, String right) {
        if (left == null || left.isBlank()) {
            return safeText(right);
        }
        if (right == null || right.isBlank()) {
            return safeText(left);
        }
        return left.trim() + "\n\n" + right.trim();
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    private String buildDocumentModeNoEvidenceReply(String question, boolean requiresFreshSearch) {
        String normalizedQuestion = safeText(question);
        if (looksLikeCapabilityQuestion(normalizedQuestion)) {
            return "当前你正在使用“当前文档问答”模式，我会优先基于所选文档回答。这个问题更像是在询问助手能力，而不是当前文档内容。如果你想了解我能做什么，请切换到“开放式提问”模式。";
        }
        if (looksLikeOpenChatQuestion(normalizedQuestion, requiresFreshSearch)) {
            return "当前你正在使用“当前文档问答”模式，我只能基于所选文档回答。这个问题更像开放式提问，例如天气、最新信息或一般交流。如果你想继续问这类问题，请切换到“开放式提问”模式。";
        }
        return StrUtil.blankToDefault(
            properties.getNoEvidenceReply(),
            "当前没有从当前文档中检索到足够证据，暂时不能给出可靠结论。你可以补充更具体的页码、章节名或关键词后再试。"
        );
    }

    private boolean looksLikeCapabilityQuestion(String normalizedQuestion) {
        if (StrUtil.isBlank(normalizedQuestion)) {
            return false;
        }
        return CAPABILITY_HINTS.stream().anyMatch(normalizedQuestion::contains);
    }

    private boolean looksLikeOpenChatQuestion(String normalizedQuestion, boolean requiresFreshSearch) {
        if (StrUtil.isBlank(normalizedQuestion)) {
            return false;
        }
        if (requiresFreshSearch) {
            return true;
        }
        if (OPEN_CHAT_HINTS.stream().anyMatch(normalizedQuestion::contains)) {
            return true;
        }
        return CHITCHAT_HINTS.stream().anyMatch(normalizedQuestion::contains);
    }

}
