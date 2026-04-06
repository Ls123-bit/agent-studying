package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.memory.ConversationMemoryContext;
import org.javaup.ai.chatagent.model.memory.ConversationSummaryPayload;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.model.HistoryPlanningContext;
import org.javaup.ai.chatagent.rag.model.KnowledgeScopeOption;
import org.javaup.ai.chatagent.rag.model.KnowledgeScopeResolution;
import org.javaup.ai.chatagent.rag.model.RagRewriteResult;
import org.javaup.ai.chatagent.service.ConversationMemoryService;
import org.javaup.ai.chatagent.support.TimeSensitiveQueryHelper;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.enums.ChatRouteType;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 聊天前置编排器。
 *
 * <p>这层不负责真正生成回答，
 * 它的职责是尽可能在模型开始输出之前，把“本轮应该怎么处理”规划清楚：</p>
 * <p>1. 路由。</p>
 * <p>2. 改写与拆分。</p>
 * <p>3. 知识域收缩。</p>
 * <p>4. 歧义澄清优先。</p>
 */
@Service
public class ChatPreparationOrchestrator {

    private final ChatRagProperties properties;
    private final ChatRouteService chatRouteService;
    private final ChatQueryRewriteService chatQueryRewriteService;
    private final KnowledgeScopeResolver knowledgeScopeResolver;
    private final DocumentKnowledgeService documentKnowledgeService;
    private final ConversationMemoryService conversationMemoryService;
    private final ClarifyFollowUpService clarifyFollowUpService;
    private final KnowledgeScopeInheritanceService knowledgeScopeInheritanceService;

    public ChatPreparationOrchestrator(ChatRagProperties properties,
                                       ChatRouteService chatRouteService,
                                       ChatQueryRewriteService chatQueryRewriteService,
                                       KnowledgeScopeResolver knowledgeScopeResolver,
                                       DocumentKnowledgeService documentKnowledgeService,
                                       ConversationMemoryService conversationMemoryService,
                                       ClarifyFollowUpService clarifyFollowUpService,
                                       KnowledgeScopeInheritanceService knowledgeScopeInheritanceService) {
        this.properties = properties;
        this.chatRouteService = chatRouteService;
        this.chatQueryRewriteService = chatQueryRewriteService;
        this.knowledgeScopeResolver = knowledgeScopeResolver;
        this.documentKnowledgeService = documentKnowledgeService;
        this.conversationMemoryService = conversationMemoryService;
        this.clarifyFollowUpService = clarifyFollowUpService;
        this.knowledgeScopeInheritanceService = knowledgeScopeInheritanceService;
    }

    /**
     * 生成当前这轮对话的执行计划。
     */
    public ConversationExecutionPlan prepare(String conversationId,
                                             String question,
                                             Long selectedDocumentId,
                                             String selectedDocumentName,
                                             LocalDate currentDate,
                                             String currentDateText) {
        /*
         * 读取长期摘要快照，并在必要时同步做增量压缩。
         */
        ConversationMemoryContext memoryContext = summarizeHistory(conversationId);
        /*
         * 这里故意把历史上下文拆成两层：
         * 1. historyPlanningContext：结构化要点，适合做路由、改写、检索提示补全；
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
         * - requiresFreshSearch: 后面是否允许网页搜索通道参与证据召回
         */
        boolean requiresCurrentDateAnchoring = TimeSensitiveQueryHelper.requiresCurrentDateAnchoring(question);
        boolean requiresFreshSearch = TimeSensitiveQueryHelper.requiresFreshSearch(question);
        /*
         * 总开关必须先于“澄清追答选择”生效。
         * 否则会出现：
         * 上一轮是 CLARIFY，本轮用户回复“1/第一个”，即使全局已经关闭 RAG，
         * 仍然能通过 follow-up selection 直接生成 RAG_CHAT 计划。
         */
        if (!properties.isEnabled()) {
            return basePlan(question, memoryContext, historyPlanningContext, historySummary, currentDate, currentDateText,
                requiresCurrentDateAnchoring, requiresFreshSearch)
                .routeType(ChatRouteType.OPEN_CHAT)
                .mode(ExecutionMode.REACT_AGENT)
                .build();
        }
        List<KnowledgeDocumentDescriptor> retrievableDocuments = documentKnowledgeService.listRetrievableDocuments();
        boolean hasRetrievableDocuments = !retrievableDocuments.isEmpty();
        /*
         * 显式文档模式的关键语义是：
         * “固定 document scope，但不关闭标准 RAG 流程”。
         *
         * 也就是说：
         * - 仍然要做 route，用来区分开放聊天 / 文档问答
         * - 仍然要做 rewrite，用来处理短追问、代词、省略信息
         * - 仍然要做 retrieve，让大文档内部继续按 chunk 召回
         *
         * 只是这里不再做“哪本文档”的猜测和澄清，而是直接以用户已选文档为检索范围。
         */
        if (selectedDocumentId != null) {
            KnowledgeDocumentDescriptor selectedDocument = retrievableDocuments.stream()
                .filter(item -> item.getDocumentId() != null && item.getDocumentId().equals(selectedDocumentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("所选文档当前不可检索: " + selectedDocumentId));
            ChatRouteType routeType = chatRouteService.route(question, historySummary, true);
            if (routeType == ChatRouteType.OPEN_CHAT) {
                return basePlan(question, memoryContext, historyPlanningContext, historySummary, currentDate, currentDateText,
                    requiresCurrentDateAnchoring, requiresFreshSearch)
                    .routeType(routeType)
                    .mode(ExecutionMode.REACT_AGENT)
                    .build();
            }
            RagRewriteResult rewriteResult = chatQueryRewriteService.rewrite(question, historySummary);
            return basePlan(question, memoryContext, historyPlanningContext, historySummary, currentDate, currentDateText,
                requiresCurrentDateAnchoring, requiresFreshSearch)
                .routeType(ChatRouteType.KNOWLEDGE)
                .mode(ExecutionMode.RAG_CHAT)
                .rewrittenQuestion(rewriteResult.getRewrittenQuestion())
                .subQuestions(rewriteResult.getSubQuestions())
                .scopeOptions(List.of(new KnowledgeScopeOption(
                    "DOC-" + selectedDocument.getDocumentId(),
                    StrUtil.blankToDefault(selectedDocumentName, selectedDocument.getDocumentName()),
                    List.of(selectedDocument.getDocumentId()),
                    List.of(selectedDocument.getLastIndexTaskId()),
                    100D,
                    List.of(selectedDocument.getDocumentName())
                )))
                .selectedDocumentIds(List.of(selectedDocument.getDocumentId()))
                .selectedTaskIds(List.of(selectedDocument.getLastIndexTaskId()))
                .build();
        }
        Optional<ClarifyFollowUpService.ClarifyFollowUpDecision> clarifyFollowUp = clarifyFollowUpService.resolve(
            conversationId,
            question
        );
        if (clarifyFollowUp.isPresent()) {
            /*
             * 这里是“澄清追答完全体”的接入点：
             * 如果当前用户输入被识别成上一轮澄清候选的选择动作，
             * 就不要再把它当成一个新的独立问题去走 route -> rewrite -> scope 这条链路。
             *
             * 否则像用户回复“1 / 第十个 / 那个手册”这种场景，
             * 很容易又被路由层判成“问题太短，需要继续澄清”，导致对话断层。
             */
            ClarifyFollowUpService.ClarifyFollowUpDecision decision = clarifyFollowUp.get();
            if (decision.action() == ClarifyFollowUpService.ClarifyFollowUpAction.SELECTED) {
                /*
                 * 选中候选后，真正继续回答的问题仍然是上一轮原问题，
                 * 只是这一次我们已经拿到了明确的知识域范围。
                 */
                return basePlan(question, memoryContext, historyPlanningContext, historySummary, currentDate, currentDateText,
                    requiresCurrentDateAnchoring, requiresFreshSearch)
                    .routeType(ChatRouteType.KNOWLEDGE)
                    .mode(ExecutionMode.RAG_CHAT)
                    .rewrittenQuestion(decision.originalQuestion())
                    .subQuestions(List.of(decision.originalQuestion()))
                    .scopeOptions(List.of(decision.selectedOption()))
                    .selectedDocumentIds(List.copyOf(decision.selectedOption().getDocumentIds()))
                    .selectedTaskIds(List.copyOf(decision.selectedOption().getTaskIds()))
                    .build();
            }
            /*
             * 如果用户是在否定上一轮候选，或者说的话仍然像是在延续上一轮选择过程，
             * 那就继续维持在 CLARIFY 模式，而不是回退成一个“普通短问题”的澄清。
             */
            return basePlan(question, memoryContext, historyPlanningContext, historySummary, currentDate, currentDateText,
                requiresCurrentDateAnchoring, requiresFreshSearch)
                .routeType(ChatRouteType.CLARIFY)
                .mode(ExecutionMode.CLARIFY)
                .rewrittenQuestion(decision.originalQuestion())
                .subQuestions(List.of(decision.originalQuestion()))
                .clarifyPrompt(decision.clarifyPrompt())
                .scopeOptions(decision.scopeOptions())
                .build();
        }
        /*
         * 这里提前把可检索文档目录查出来并复用到后面的 scope 解析，
         * 是为了避免同一轮里“先判断有没有文档，再解析文档范围”重复扫两次目录。
         */
        /*
         * routeType 是第一层“方向判断”：
         * 它只回答一个问题：这轮问题更应该走开放式对话、知识问答，还是先澄清。
         */
        ChatRouteType routeType = chatRouteService.route(question, historySummary, hasRetrievableDocuments);
        if (routeType == ChatRouteType.OPEN_CHAT) {
            /*
             * OPEN_CHAT 不做改写、不做知识域收缩，直接交给 Agent 执行路径。
             * 这里的重点不是“马上回答”，而是尽早结束知识问答分支的额外开销。
             */
            return basePlan(question, memoryContext, historyPlanningContext, historySummary, currentDate, currentDateText,
                requiresCurrentDateAnchoring, requiresFreshSearch)
                .routeType(routeType)
                .mode(ExecutionMode.REACT_AGENT)
                .build();
        }
        if (routeType == ChatRouteType.CLARIFY) {
            /*
             * 路由阶段已经足够确定“应该先追问”，
             * 那就不再浪费一次改写和知识域解析成本，直接产出澄清计划。
             */
            return basePlan(question, memoryContext, historyPlanningContext, historySummary, currentDate, currentDateText,
                requiresCurrentDateAnchoring, requiresFreshSearch)
                .routeType(routeType)
                .mode(ExecutionMode.CLARIFY)
                .clarifyPrompt(buildGenericClarifyPrompt(question))
                .build();
        }

        /*
         * 只有明确进入 KNOWLEDGE 分支后，才开始做“改写 + 拆分”。
         * 这里的改写结果会直接影响：
         * 1. 知识域解析准确率
         * 2. 子问题检索命中率
         */
        RagRewriteResult rewriteResult = chatQueryRewriteService.rewrite(question, historySummary);
        /*
         * 知识域解析不是做最终回答，而是把“应该查哪些文档”先收缩清楚。
         * 如果知识域仍然有歧义，它会返回 clarifyRequired=true，让链路回退成澄清。
         */
        KnowledgeScopeResolution scopeResolution = knowledgeScopeResolver.resolve(
            rewriteResult.getRewrittenQuestion(),
            historySummary,
            retrievableDocuments
        );

        if (scopeResolution.isClarifyRequired()) {
            /*
             * 在真正回退成澄清之前，先尝试从最近一次成功的知识问答轮次继承知识域。
             * 这样"选了产品手册 → 问协议配置 → 问常见故障"这类连续追问，
             * 不会每轮都被追问"你想问的是哪个业务系统"。
             */
            Optional<KnowledgeScopeInheritanceService.InheritedScope> inherited =
                knowledgeScopeInheritanceService.tryInherit(conversationId, rewriteResult.getRewrittenQuestion());
            if (inherited.isPresent()) {
                KnowledgeScopeInheritanceService.InheritedScope scope = inherited.get();
                return basePlan(question, memoryContext, historyPlanningContext, historySummary, currentDate, currentDateText,
                    requiresCurrentDateAnchoring, requiresFreshSearch)
                    .routeType(ChatRouteType.KNOWLEDGE)
                    .mode(ExecutionMode.RAG_CHAT)
                    .rewrittenQuestion(rewriteResult.getRewrittenQuestion())
                    .subQuestions(rewriteResult.getSubQuestions())
                    .scopeOptions(scope.scopeOptions())
                    .selectedDocumentIds(scope.selectedDocumentIds())
                    .selectedTaskIds(scope.selectedTaskIds())
                    .build();
            }
            /*
             * 这里说明：问题虽然总体上属于知识问答，但仍然无法确认用户到底指向哪个业务系统。
             * 这种情况澄清优先级高于检索，直接转成 CLARIFY 执行模式。
             */
            return basePlan(question, memoryContext, historyPlanningContext, historySummary, currentDate, currentDateText,
                requiresCurrentDateAnchoring, requiresFreshSearch)
                .routeType(ChatRouteType.CLARIFY)
                .mode(ExecutionMode.CLARIFY)
                .rewrittenQuestion(rewriteResult.getRewrittenQuestion())
                .subQuestions(rewriteResult.getSubQuestions())
                .clarifyPrompt(scopeResolution.getClarifyPrompt())
                .scopeOptions(scopeResolution.getOptions())
                .build();
        }

        if (scopeResolution.getSelectedDocumentIds() == null || scopeResolution.getSelectedDocumentIds().isEmpty()) {
            /*
             * 这是这次改造里很关键的一个分支：
             * 旧逻辑在知识域没有真正收敛时，会把所有候选 scope 一起放进检索范围，
             * 相当于“收不拢就放大全库”，最终把答案上下文冲淡。
             *
             * 现在改成两种更保守的策略：
             * 1. 对强时效问题，允许进入 web-only 的 RAG 降级。
             *    也就是内部知识域没命中时，不强行掺入无关内部文档，
             *    只保留外部网页通道继续找证据。
             * 2. 对非时效问题，优先回到澄清，而不是猜着扩大范围去搜。
             */
            if (requiresFreshSearch) {
                return basePlan(question, memoryContext, historyPlanningContext, historySummary, currentDate, currentDateText,
                    requiresCurrentDateAnchoring, requiresFreshSearch)
                    .routeType(ChatRouteType.KNOWLEDGE)
                    .mode(ExecutionMode.RAG_CHAT)
                    .rewrittenQuestion(rewriteResult.getRewrittenQuestion())
                    .subQuestions(rewriteResult.getSubQuestions())
                    .scopeOptions(scopeResolution.getOptions())
                    .build();
            }
            List<KnowledgeScopeOption> clarifyOptions = scopeResolution.getOptions() == null ? List.of() : scopeResolution.getOptions();
            return basePlan(question, memoryContext, historyPlanningContext, historySummary, currentDate, currentDateText,
                requiresCurrentDateAnchoring, requiresFreshSearch)
                .routeType(ChatRouteType.CLARIFY)
                .mode(ExecutionMode.CLARIFY)
                .rewrittenQuestion(rewriteResult.getRewrittenQuestion())
                .subQuestions(rewriteResult.getSubQuestions())
                .clarifyPrompt(clarifyOptions.isEmpty()
                    ? buildGenericClarifyPrompt(question)
                    : knowledgeScopeResolver.buildClarifyPromptForOptions(clarifyOptions))
                .scopeOptions(clarifyOptions)
                .build();
        }

        /*
         * 走到这里，说明三件事都成立：
         * 1. 当前问题适合知识问答
         * 2. 改写和拆分已经完成
         * 3. 检索范围已经收缩到具体文档集合
         *
         * 因此这时才真正产出 RAG_CHAT 执行计划。
         */
        return basePlan(question, memoryContext, historyPlanningContext, historySummary, currentDate, currentDateText,
            requiresCurrentDateAnchoring, requiresFreshSearch)
            .routeType(ChatRouteType.KNOWLEDGE)
            .mode(ExecutionMode.RAG_CHAT)
            .rewrittenQuestion(rewriteResult.getRewrittenQuestion())
            .subQuestions(rewriteResult.getSubQuestions())
            .scopeOptions(scopeResolution.getOptions())
            .selectedDocumentIds(scopeResolution.getSelectedDocumentIds())
            .selectedTaskIds(scopeResolution.getSelectedTaskIds())
            .build();
    }

    /**
     * 构造所有模式都会复用的基础计划部分。
     */
    private ConversationExecutionPlan.ConversationExecutionPlanBuilder basePlan(String question,
                                                                                ConversationMemoryContext memoryContext,
                                                                                HistoryPlanningContext historyPlanningContext,
                                                                                String historySummary,
                                                                                LocalDate currentDate,
                                                                                String currentDateText,
                                                                                boolean requiresCurrentDateAnchoring,
                                                                                boolean requiresFreshSearch) {
        /*
         * 这里先把所有执行模式都会共用的字段放进去，
         * 后面的 OPEN_CHAT / CLARIFY / RAG_CHAT 再在这个 builder 基础上补充分支特有字段。
         *
         * 这样做的好处是：
         * 1. plan 的公共字段只维护一处
         * 2. 不同分支返回的对象结构更统一
         */
        return ConversationExecutionPlan.builder()
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
     * 历史摘要只保留最近 N 轮，避免改写和路由提示词无限增长。
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
         * 2. 历史信息过多时，路由/改写阶段又退化回“吃一整坨历史文本”。
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
         * - 已确认事实：减少后续改写和路由时把历史事实重新判成未知
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

    /**
     * 对明显模糊的问题给一个通用追问。
     */
    private String buildGenericClarifyPrompt(String question) {
        /*
         * “推荐”类问题通常不是单一缺失字段，而是用户需求本身还没有收敛。
         * 所以这里给一个更开放的追问，帮助用户先限定方向。
         */
        if (question != null && question.contains("推荐")) {
            return "可以先补充下你想推荐的是哪一类内容，例如课程方向、业务系统、规则说明还是操作流程。";
        }
        /*
         * 其他模糊问题则统一追问“系统名 / 模块名 / 业务关键词”，
         * 这是当前知识域收缩最需要的三个信息。
         */
        return "你这个问题还差一点上下文。可以补充更具体的系统名称、模块名称或业务关键词，我再继续帮你查。";
    }

}
