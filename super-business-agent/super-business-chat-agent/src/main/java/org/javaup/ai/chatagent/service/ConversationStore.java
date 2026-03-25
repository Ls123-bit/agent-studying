package org.javaup.ai.chatagent.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.javaup.ai.chatagent.enums.ChatTurnStatus;
import org.javaup.ai.chatagent.model.ConversationTurnView;
import org.javaup.ai.chatagent.model.SearchReference;

/**
 * 业务对话会话仓储抽象。
 *
 * <p>这里持久化的是“产品层会话数据”，也就是前端需要展示和查询的内容：
 * 会话列表、单轮问答、思考过程、引用来源、推荐问题、耗时等。
 * 这层和 Spring AI Alibaba 的 checkpoint 是互补关系：
 * checkpoint 负责 ReactAgent 的运行记忆，
 * 这里负责业务系统自己的会话视图。</p>
 */
public interface ConversationStore {

    ConversationTurnView startTurn(String conversationId, String question);

    void completeTurn(String conversationId,
                      long turnId,
                      String answer,
                      List<String> thinkingSteps,
                      List<SearchReference> references,
                      List<String> recommendations,
                      List<String> usedTools,
                      ChatTurnStatus status,
                      String errorMessage,
                      Long firstResponseTimeMs,
                      Long totalResponseTimeMs);

    Optional<SessionRecord> getSessionRecord(String conversationId);

    List<SessionRecord> listSessionRecords();

    void deleteSession(String conversationId);

    record SessionRecord(
        String conversationId,
        boolean running,
        Instant createdAt,
        Instant updatedAt,
        List<ConversationTurnView> turns
    ) {
    }
}
