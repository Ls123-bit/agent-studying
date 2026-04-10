package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.rag.model.ConversationIntentRelationType;
import org.javaup.ai.chatagent.rag.model.ConversationIntentResolution;
import org.javaup.ai.chatagent.rag.model.ConversationRetrievalPlanningResult;
import org.javaup.ai.chatagent.rag.model.RagRewriteResult;
import org.javaup.ai.chatagent.rag.model.RetrievalAnchorResolution;
import org.javaup.ai.chatagent.service.ConversationArchiveStore;
import org.javaup.enums.ChatTurnStatus;
import org.springframework.stereotype.Service;

import java.util.List;

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
                                                    String historySummary) {
        List<ConversationExchangeView> recentCompletedExchanges = listRecentCompletedExchanges(conversationId);
        String previousAnchorDescription = conversationRetrievalAnchorService.describePreviousAnchor(conversationId);
        ConversationIntentResolution intentResolution = conversationIntentResolutionService.resolve(
            question,
            recentCompletedExchanges,
            previousAnchorDescription
        );
        String rewriteHistoryContext = buildRewriteHistoryContext(historySummary, previousAnchorDescription, intentResolution);
        RagRewriteResult rewriteResult = chatQueryRewriteService.rewrite(question, rewriteHistoryContext, intentResolution);
        RetrievalAnchorResolution anchorResolution = conversationRetrievalAnchorService.resolve(
            conversationId,
            question,
            rewriteResult,
            intentResolution
        );
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
