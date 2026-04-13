package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.javaup.ai.chatagent.rag.model.ConversationIntentRelationType;
import org.javaup.ai.chatagent.rag.model.ConversationIntentResolution;
import org.javaup.ai.chatagent.rag.model.ConversationRetrievalPlanningResult;
import org.javaup.ai.chatagent.rag.model.RagRewriteResult;
import org.javaup.ai.chatagent.rag.model.RetrievalAnchorResolution;
import org.javaup.ai.chatagent.service.ConversationArchiveStore;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.enums.ChatTurnStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 文档问答模式下的检索规划服务。
 *
 * <p>它负责把“语义规划 -> 受约束改写 -> 锚点与检索计划”这三步收拢成一条清晰主链，</p>
 * <p>避免 rewrite 在没有语义约束时先把问题带偏，再让后面的检索层被动补救。</p>
 */
@Service
public class ConversationRetrievalPlanningService {

    private static final int RECENT_EXCHANGE_LIMIT = 8;

    private final ConversationArchiveStore conversationArchiveStore;
    private final ConversationIntentResolutionService conversationIntentResolutionService;
    private final ChatQueryRewriteService chatQueryRewriteService;
    private final ConversationRetrievalAnchorService conversationRetrievalAnchorService;

    public ConversationRetrievalPlanningService(ConversationArchiveStore conversationArchiveStore,
                                                ConversationIntentResolutionService conversationIntentResolutionService,
                                                ChatQueryRewriteService chatQueryRewriteService,
                                                ConversationRetrievalAnchorService conversationRetrievalAnchorService) {
        this.conversationArchiveStore = conversationArchiveStore;
        this.conversationIntentResolutionService = conversationIntentResolutionService;
        this.chatQueryRewriteService = chatQueryRewriteService;
        this.conversationRetrievalAnchorService = conversationRetrievalAnchorService;
    }

    /**
     * 生成当前轮的完整检索规划结果。
     */
    public ConversationRetrievalPlanningResult plan(String conversationId,
                                                    String question,
                                                    String historySummary,
                                                    ConversationTraceRecorder traceRecorder,
                                                    String executionMode) {
        List<ConversationExchangeView> recentCompletedExchanges = listRecentCompletedExchanges(conversationId);
        String previousAnchorDescription = conversationRetrievalAnchorService.describePreviousAnchor(conversationId);
        ConversationTraceRecorder.StageHandle intentStage = traceRecorder == null
            ? null
            : traceRecorder.startStage(ConversationTraceStageCode.INTENT, executionMode, "正在分析当前问题与上文关系。", null);
        ConversationIntentResolution intentResolution;
        try {
            intentResolution = conversationIntentResolutionService.resolve(
                question,
                recentCompletedExchanges,
                previousAnchorDescription,
                traceRecorder
            );
            if (traceRecorder != null) {
                traceRecorder.completeStage(intentStage, "会话关系解析完成。", Map.ofEntries(
                    Map.entry("originalQuestion", StrUtil.blankToDefault(question, "")),
                    Map.entry("previousAnchorDescription", StrUtil.blankToDefault(previousAnchorDescription, "")),
                    Map.entry("relationType", intentResolution == null ? "" : StrUtil.blankToDefault(String.valueOf(intentResolution.getRelationType()), "")),
                    Map.entry("resolvedTopic", intentResolution == null ? "" : StrUtil.blankToDefault(intentResolution.getResolvedTopic(), "")),
                    Map.entry("resolvedFacet", intentResolution == null ? "" : StrUtil.blankToDefault(intentResolution.getResolvedFacet(), "")),
                    Map.entry("informationNeed", intentResolution == null ? "" : StrUtil.blankToDefault(intentResolution.getInformationNeed(), "")),
                    Map.entry("answerShape", intentResolution == null ? "" : StrUtil.blankToDefault(String.valueOf(intentResolution.getAnswerShape()), "")),
                    Map.entry("retrievalMode", intentResolution == null ? "" : StrUtil.blankToDefault(String.valueOf(intentResolution.getRetrievalMode()), "")),
                    Map.entry("retrievalQuery", intentResolution == null ? "" : StrUtil.blankToDefault(intentResolution.getRetrievalQuery(), "")),
                    Map.entry("retrievalSubQuestions", intentResolution == null || intentResolution.getRetrievalSubQuestions() == null ? List.of() : intentResolution.getRetrievalSubQuestions()),
                    Map.entry("softSectionHints", intentResolution == null || intentResolution.getSoftSectionHints() == null ? List.of() : intentResolution.getSoftSectionHints()),
                    Map.entry("queryContextHints", intentResolution == null || intentResolution.getQueryContextHints() == null ? List.of() : intentResolution.getQueryContextHints()),
                    Map.entry("confidence", intentResolution == null || intentResolution.getConfidence() == null ? "" : String.valueOf(intentResolution.getConfidence())),
                    Map.entry("rationale", intentResolution == null ? "" : StrUtil.blankToDefault(intentResolution.getRationale(), "")),
                    Map.entry("rawModelOutput", intentResolution == null ? "" : StrUtil.blankToDefault(intentResolution.getRawModelOutput(), ""))
                ));
            }
        }
        catch (RuntimeException exception) {
            if (traceRecorder != null) {
                traceRecorder.failStage(intentStage, "会话关系解析失败。", exception.getMessage(), null);
            }
            throw exception;
        }

        String rewriteHistoryContext = buildRewriteHistoryContext(historySummary, previousAnchorDescription, intentResolution);
        ConversationTraceRecorder.StageHandle rewriteStage = traceRecorder == null
            ? null
            : traceRecorder.startStage(ConversationTraceStageCode.REWRITE, executionMode, "正在生成检索友好的问题表达。", java.util.Map.of(
                "originalQuestion", StrUtil.blankToDefault(question, ""),
                "historyContext", StrUtil.blankToDefault(rewriteHistoryContext, "")
            ));
        RagRewriteResult rewriteResult;
        try {
            rewriteResult = chatQueryRewriteService.rewrite(question, rewriteHistoryContext, intentResolution, traceRecorder);
            if (traceRecorder != null) {
                traceRecorder.completeStage(rewriteStage, "问题改写完成。", java.util.Map.of(
                    "originalQuestion", StrUtil.blankToDefault(question, ""),
                    "historyContext", StrUtil.blankToDefault(rewriteHistoryContext, ""),
                    "rewriteQuestion", rewriteResult == null ? "" : StrUtil.blankToDefault(rewriteResult.getRewrittenQuestion(), ""),
                    "subQuestions", rewriteResult == null ? List.of() : (rewriteResult.getSubQuestions() == null ? List.of() : rewriteResult.getSubQuestions()),
                    "rawModelOutput", rewriteResult == null ? "" : StrUtil.blankToDefault(rewriteResult.getRawModelOutput(), "")
                ));
            }
        }
        catch (RuntimeException exception) {
            if (traceRecorder != null) {
                traceRecorder.failStage(rewriteStage, "问题改写失败。", exception.getMessage(), null);
            }
            throw exception;
        }

        ConversationTraceRecorder.StageHandle routeStage = traceRecorder == null
            ? null
            : traceRecorder.startStage(ConversationTraceStageCode.ROUTE, executionMode, "正在生成检索计划与锚点。", null);
        RetrievalAnchorResolution anchorResolution;
        try {
            anchorResolution = conversationRetrievalAnchorService.resolve(
                conversationId,
                question,
                rewriteResult,
                intentResolution
            );
            if (traceRecorder != null) {
                traceRecorder.completeStage(routeStage, "检索计划生成完成。", java.util.Map.of(
                    "originalQuestion", StrUtil.blankToDefault(question, ""),
                    "retrievalQuestion", anchorResolution == null || anchorResolution.getRetrievalPlan() == null ? "" : StrUtil.blankToDefault(anchorResolution.getRetrievalPlan().getRetrievalQuestion(), ""),
                    "retrievalSubQuestions", anchorResolution == null || anchorResolution.getRetrievalPlan() == null || anchorResolution.getRetrievalPlan().getSubQuestions() == null
                        ? List.of()
                        : anchorResolution.getRetrievalPlan().getSubQuestions(),
                    "anchorApplied", anchorResolution != null && anchorResolution.getAnchorContext() != null && anchorResolution.getAnchorContext().isAnchorApplied(),
                    "targetSectionHint", anchorResolution == null || anchorResolution.getAnchorContext() == null ? "" : StrUtil.blankToDefault(anchorResolution.getAnchorContext().getTargetSectionHint(), ""),
                    "rootTopic", anchorResolution == null || anchorResolution.getAnchorContext() == null ? "" : StrUtil.blankToDefault(anchorResolution.getAnchorContext().getRootTopic(), ""),
                    "rootSectionCode", anchorResolution == null || anchorResolution.getAnchorContext() == null ? "" : StrUtil.blankToDefault(anchorResolution.getAnchorContext().getRootSectionCode(), ""),
                    "rootSectionTitle", anchorResolution == null || anchorResolution.getAnchorContext() == null ? "" : StrUtil.blankToDefault(anchorResolution.getAnchorContext().getRootSectionTitle(), "")
                ));
            }
        }
        catch (RuntimeException exception) {
            if (traceRecorder != null) {
                traceRecorder.failStage(routeStage, "检索计划生成失败。", exception.getMessage(), null);
            }
            throw exception;
        }
        return new ConversationRetrievalPlanningResult(rewriteResult, intentResolution, anchorResolution);
    }

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

    private String buildRewriteHistoryContext(String historySummary,
                                              String previousAnchorDescription,
                                              ConversationIntentResolution intentResolution) {
        if (intentResolution == null || intentResolution.getRelationType() == null || intentResolution.getRelationType() == ConversationIntentRelationType.UNKNOWN) {
            return StrUtil.blankToDefault(historySummary, "");
        }
        if (intentResolution.getRelationType() == ConversationIntentRelationType.FRESH_TOPIC) {
            return StrUtil.blankToDefault(historySummary, "");
        }
        StringBuilder builder = new StringBuilder();
        if (StrUtil.isNotBlank(previousAnchorDescription) && !"无".equals(previousAnchorDescription)) {
            builder.append("上一轮锚点状态：\n").append(previousAnchorDescription.trim()).append("\n\n");
        }
        if (StrUtil.isNotBlank(intentResolution.getResolvedTopic())) {
            builder.append("当前主题：").append(intentResolution.getResolvedTopic().trim()).append('\n');
        }
        if (StrUtil.isNotBlank(intentResolution.getResolvedFacet())) {
            builder.append("当前面向：").append(intentResolution.getResolvedFacet().trim()).append('\n');
        }
        if (StrUtil.isNotBlank(intentResolution.getInformationNeed())) {
            builder.append("当前信息需求：").append(intentResolution.getInformationNeed().trim()).append('\n');
        }
        if (intentResolution.getRetrievalMode() != null) {
            builder.append("检索模式：").append(intentResolution.getRetrievalMode().name()).append('\n');
        }
        if (StrUtil.isNotBlank(intentResolution.getRetrievalQuery())) {
            builder.append("计划检索问题：").append(intentResolution.getRetrievalQuery().trim()).append('\n');
        }
        if (intentResolution.getRetrievalSubQuestions() != null && !intentResolution.getRetrievalSubQuestions().isEmpty()) {
            builder.append("计划检索子问题：").append(intentResolution.getRetrievalSubQuestions()).append('\n');
        }
        String compactContext = builder.toString().trim();
        if (compactContext.isBlank()) {
            return StrUtil.blankToDefault(historySummary, "");
        }
        return compactContext;
    }
}
