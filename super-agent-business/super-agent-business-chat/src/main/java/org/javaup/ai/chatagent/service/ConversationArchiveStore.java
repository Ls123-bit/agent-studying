package org.javaup.ai.chatagent.service;

import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.enums.ChatTurnStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 业务对话归档存储抽象。
 *
 * <p>这里持久化的是“产品层会话数据”，也就是前端需要展示和查询的内容：
 * 会话列表、单轮问答、思考过程、引用来源、推荐问题、耗时等。
 * 这层和 Spring AI Alibaba 的 checkpoint 是互补关系：
 * checkpoint 负责 ReactAgent 的运行记忆，
 * 这里负责业务系统自己的会话视图。</p>
 */
public interface ConversationArchiveStore {

    ConversationExchangeView startExchange(String conversationId, String question);

    void completeExchange(String conversationId,
                          long exchangeId,
                          String answer,
                          List<String> thinkingSteps,
                          List<SearchReference> references,
                          List<String> recommendations,
                          List<String> usedTools,
                          ChatTurnStatus status,
                          String errorMessage,
                          Long firstResponseTimeMs,
                          Long totalResponseTimeMs);

    Optional<ConversationArchiveRecord> getSessionRecord(String conversationId);

    List<ConversationArchiveRecord> listSessionRecords();

    ConversationRemovalResult deleteSession(String conversationId);

    record ConversationArchiveRecord(
        String conversationId,
        boolean running,
        Instant createdAt,
        Instant updatedAt,
        List<ConversationExchangeView> exchanges
    ) {
    }

    record ConversationRemovalResult(
        int removedDialogueCount,
        int removedExchangeCount
    ) {
    }
}
