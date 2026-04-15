package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.core.guidance.GuidanceDecision;
import org.javaup.ai.chatagent.rag.core.guidance.SectionGuidanceService;
import org.javaup.ai.chatagent.rag.core.intent.SectionIntentResolver;
import org.javaup.ai.chatagent.rag.core.intent.SubQuestionIntent;
import org.javaup.ai.chatagent.rag.core.rewrite.ChatQueryRewriteService;
import org.javaup.ai.chatagent.rag.core.rewrite.RewriteResult;
import org.javaup.ai.chatagent.rag.model.ConversationRetrievalPlanningResult;
import org.javaup.ai.chatagent.service.ConversationArchiveStore;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.enums.ChatTurnStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 检索规划服务（重构版）。
 *
 * <p>对标 ragent 的 RAGChatServiceImpl 前半段编排逻辑。
 * 新架构流程：<b>加载历史 → 独立改写 → 章节意图分类 → 歧义检测</b>。</p>
 *
 * <p>与旧版的核心区别：</p>
 * <ul>
 *   <li>移除了 ConversationIntentResolutionService — 不再做13字段的复杂意图解析</li>
 *   <li>移除了 DocumentNavigationEngine — 不再做硬性章节锁定</li>
 *   <li>改写阶段完全独立，不受任何上游结果约束</li>
 *   <li>章节意图分类只产生软路由信号，不产生硬过滤条件</li>
 *   <li>新增歧义检测，不确定时主动追问而非猜测</li>
 * </ul>
 */
@Slf4j
@Service
public class ConversationRetrievalPlanningService {

    private static final int RECENT_EXCHANGE_LIMIT = 8;

    private final ConversationArchiveStore conversationArchiveStore;
    private final ChatQueryRewriteService chatQueryRewriteService;
    private final SectionIntentResolver sectionIntentResolver;
    private final SectionGuidanceService sectionGuidanceService;

    public ConversationRetrievalPlanningService(ConversationArchiveStore conversationArchiveStore,
                                                ChatQueryRewriteService chatQueryRewriteService,
                                                SectionIntentResolver sectionIntentResolver,
                                                SectionGuidanceService sectionGuidanceService) {
        this.conversationArchiveStore = conversationArchiveStore;
        this.chatQueryRewriteService = chatQueryRewriteService;
        this.sectionIntentResolver = sectionIntentResolver;
        this.sectionGuidanceService = sectionGuidanceService;
    }

    /**
     * 生成当前轮的检索规划结果。
     *
     * <p>流程：加载历史 → 独立改写 → 章节意图分类 → 歧义检测。
     * 每个阶段独立运作，前一阶段的错误不会放大到后续阶段。</p>
     */
    public ConversationRetrievalPlanningResult plan(String conversationId,
                                                    Long documentId,
                                                    String question,
                                                    ConversationTraceRecorder traceRecorder) {
        // ── 1. 加载历史 ──
        List<ConversationExchangeView> recentExchanges = listRecentCompletedExchanges(conversationId);

        // ── 2. 独立改写（不依赖任何上游结果）──
        RewriteResult rewriteResult = executeRewrite(question, recentExchanges, traceRecorder);

        // ── 3. 章节意图分类（软路由，不硬锁定）──
        List<SubQuestionIntent> subQuestionIntents = executeIntentClassify(
                documentId, rewriteResult, traceRecorder);

        // ── 4. 歧义检测（不确定就追问）──
        GuidanceDecision guidance = executeGuidanceCheck(question, subQuestionIntents, traceRecorder);

        return new ConversationRetrievalPlanningResult(rewriteResult, subQuestionIntents, guidance);
    }

    // ── Stage 1: 改写 ──

    private RewriteResult executeRewrite(String question,
                                         List<ConversationExchangeView> recentExchanges,
                                         ConversationTraceRecorder traceRecorder) {
        ConversationTraceRecorder.StageHandle stage = traceRecorder == null ? null
                : traceRecorder.startStage(ConversationTraceStageCode.REWRITE, "RAG_CHAT",
                "正在改写问题。", null);
        try {
            RewriteResult result = chatQueryRewriteService.rewriteWithSplit(question, recentExchanges);
            if (traceRecorder != null) {
                traceRecorder.completeStage(stage, "问题改写完成。", Map.of(
                        "originalQuestion", StrUtil.blankToDefault(question, ""),
                        "rewrittenQuestion", result == null ? "" : StrUtil.blankToDefault(result.rewrittenQuestion(), ""),
                        "subQuestions", result == null ? List.of() : result.subQuestions()
                ));
            }
            return result;
        } catch (RuntimeException e) {
            if (traceRecorder != null) {
                traceRecorder.failStage(stage, "问题改写失败。", e.getMessage(), null);
            }
            throw e;
        }
    }

    // ── Stage 2: 意图分类 ──

    private List<SubQuestionIntent> executeIntentClassify(Long documentId,
                                                          RewriteResult rewriteResult,
                                                          ConversationTraceRecorder traceRecorder) {
        ConversationTraceRecorder.StageHandle stage = traceRecorder == null ? null
                : traceRecorder.startStage(ConversationTraceStageCode.INTENT, "RAG_CHAT",
                "正在进行章节意图分类。", null);
        try {
            List<SubQuestionIntent> intents = sectionIntentResolver.resolve(documentId, rewriteResult);
            if (traceRecorder != null) {
                traceRecorder.completeStage(stage, "章节意图分类完成。", Map.of(
                        "subQuestionCount", intents.size(),
                        "intents", intents.stream()
                                .map(si -> Map.of(
                                        "subQuestion", si.subQuestion(),
                                        "topSections", si.sectionScores().stream()
                                                .map(s -> s.node().getDisplayPath() + "=" + s.score())
                                                .toList()
                                ))
                                .toList()
                ));
            }
            return intents;
        } catch (RuntimeException e) {
            if (traceRecorder != null) {
                traceRecorder.failStage(stage, "章节意图分类失败。", e.getMessage(), null);
            }
            throw e;
        }
    }

    // ── Stage 3: 歧义检测 ──

    private GuidanceDecision executeGuidanceCheck(String question,
                                                   List<SubQuestionIntent> subQuestionIntents,
                                                   ConversationTraceRecorder traceRecorder) {
        try {
            GuidanceDecision decision = sectionGuidanceService.detectAmbiguity(question, subQuestionIntents);
            if (decision.isPrompt()) {
                log.info("[规划] 歧义检测触发: question='{}'", question);
            }
            return decision;
        } catch (Exception e) {
            log.warn("[规划] 歧义检测异常，跳过: {}", e.getMessage());
            return GuidanceDecision.none();
        }
    }

    // ── 历史加载 ──

    private List<ConversationExchangeView> listRecentCompletedExchanges(String conversationId) {
        if (StrUtil.isBlank(conversationId)) {
            return List.of();
        }
        return conversationArchiveStore.listRecentExchanges(conversationId, RECENT_EXCHANGE_LIMIT).stream()
                .filter(exchange -> exchange != null
                        && exchange.getStatus() == ChatTurnStatus.COMPLETED
                        && StrUtil.isNotBlank(exchange.getQuestion())
                        && StrUtil.isNotBlank(exchange.getAnswer()))
                .toList();
    }
}
