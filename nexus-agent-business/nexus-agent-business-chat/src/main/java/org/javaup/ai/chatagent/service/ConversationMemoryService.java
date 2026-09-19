package org.javaup.ai.chatagent.service;

import org.javaup.ai.chatagent.model.ConversationMemorySummaryView;
import org.javaup.ai.chatagent.model.memory.ConversationMemoryContext;

/*
会话记忆管理的核心接口

 */
public interface ConversationMemoryService {

    ConversationMemoryContext loadMemoryContext(String conversationId);//加载完整的记忆上下文

    default ConversationMemoryContext loadMemoryContext(String conversationId, ConversationTraceRecorder traceRecorder) {//带链路追踪的版本
        return loadMemoryContext(conversationId);
    }

    void refreshConversationSummaryAsync(String conversationId);//异步刷新摘要（预热）

    ConversationMemorySummaryView getConversationSummary(String conversationId);//获取当前摘要（不触发刷新）

    ConversationMemorySummaryView rebuildConversationSummary(String conversationId);//删除并重建摘要

    void deleteConversationSummary(String conversationId);//删除摘要
}
