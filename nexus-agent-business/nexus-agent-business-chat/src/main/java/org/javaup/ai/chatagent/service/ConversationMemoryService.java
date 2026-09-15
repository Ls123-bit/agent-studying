package org.javaup.ai.chatagent.service;

import org.javaup.ai.chatagent.model.ConversationMemorySummaryView;
import org.javaup.ai.chatagent.model.memory.ConversationMemoryContext;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
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
