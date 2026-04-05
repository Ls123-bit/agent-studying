package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.model.memory.ConversationMemoryContext;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.model.KnowledgeScopeResolution;
import org.javaup.ai.chatagent.rag.model.RagRewriteResult;
import org.javaup.ai.chatagent.service.ConversationMemoryService;
import org.javaup.ai.chatagent.support.TimeSensitiveQueryHelper;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.enums.ChatRouteType;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
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
                                             LocalDate currentDate,
                                             String currentDateText) {
        /*
         * 读取长期摘要快照，并在必要时同步做增量压缩。
         */
        ConversationMemoryContext memoryContext = summarizeHistory(conversationId);
        String historySummary = memoryContext.getAssembledHistory();
        /*
         * 这两个布尔量不是给当前方法自己用的，而是给后续执行计划做“能力开关”：
         * - requiresCurrentDateAnchoring: 后面是否要把“今天/本周/今年”解释成当前日期
         * - requiresFreshSearch: 后面是否允许网页搜索通道参与证据召回
         */
        boolean requiresCurrentDateAnchoring = TimeSensitiveQueryHelper.requiresCurrentDateAnchoring(question);
        boolean requiresFreshSearch = TimeSensitiveQueryHelper.requiresFreshSearch(question);
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
                return basePlan(question, memoryContext, currentDate, currentDateText, requiresCurrentDateAnchoring, requiresFreshSearch)
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
            return basePlan(question, memoryContext, currentDate, currentDateText, requiresCurrentDateAnchoring, requiresFreshSearch)
                .routeType(ChatRouteType.CLARIFY)
                .mode(ExecutionMode.CLARIFY)
                .rewrittenQuestion(decision.originalQuestion())
                .subQuestions(List.of(decision.originalQuestion()))
                .clarifyPrompt(decision.clarifyPrompt())
                .scopeOptions(decision.scopeOptions())
                .build();
        }

        /*
         * 整个 RAG 编排开关关闭时，当前轮就不要再走知识问答路径了，
         * 直接把执行模式降级成 REACT_AGENT。
         * 这里仍然会生成基础 plan，是为了让外层链路保持统一，不需要再额外分支。
         */
        if (!properties.isEnabled()) {
            return basePlan(question, memoryContext, currentDate, currentDateText, requiresCurrentDateAnchoring, requiresFreshSearch)
                .routeType(ChatRouteType.OPEN_CHAT)
                .mode(ExecutionMode.REACT_AGENT)
                .build();
        }

        /*
         * 如果系统里当前根本没有任何可检索文档，
         * 那知识问答路径必然拿不到证据，路由阶段就应该知道这件事。
         */
        List<KnowledgeDocumentDescriptor> retrievableDocuments = documentKnowledgeService.listRetrievableDocuments();
        boolean hasRetrievableDocuments = !retrievableDocuments.isEmpty();
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
            return basePlan(question, memoryContext, currentDate, currentDateText, requiresCurrentDateAnchoring, requiresFreshSearch)
                .routeType(routeType)
                .mode(ExecutionMode.REACT_AGENT)
                .build();
        }
        if (routeType == ChatRouteType.CLARIFY) {
            /*
             * 路由阶段已经足够确定“应该先追问”，
             * 那就不再浪费一次改写和知识域解析成本，直接产出澄清计划。
             */
            return basePlan(question, memoryContext, currentDate, currentDateText, requiresCurrentDateAnchoring, requiresFreshSearch)
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
                return basePlan(question, memoryContext, currentDate, currentDateText, requiresCurrentDateAnchoring, requiresFreshSearch)
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
            return basePlan(question, memoryContext, currentDate, currentDateText, requiresCurrentDateAnchoring, requiresFreshSearch)
                .routeType(ChatRouteType.CLARIFY)
                .mode(ExecutionMode.CLARIFY)
                .rewrittenQuestion(rewriteResult.getRewrittenQuestion())
                .subQuestions(rewriteResult.getSubQuestions())
                .clarifyPrompt(scopeResolution.getClarifyPrompt())
                .scopeOptions(scopeResolution.getOptions())
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
        return basePlan(question, memoryContext, currentDate, currentDateText, requiresCurrentDateAnchoring, requiresFreshSearch)
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
            .historySummary(memoryContext.getAssembledHistory())
            .longTermSummary(memoryContext.getLongTermSummary())
            .recentHistoryTranscript(memoryContext.getRecentTranscript())
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
