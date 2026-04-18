package org.javaup.ai.chatagent.service;

import org.javaup.ai.chatagent.model.ConversationMemorySummaryView;
import org.javaup.ai.chatagent.model.memory.ConversationMemoryContext;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
/**
 * 会话长期记忆服务。
 *
 * <p>职责分成两段：</p>
 * <p>1. 回答前：把“长期摘要 + 最近原文窗口”组装成可供编排器直接使用的上下文。</p>
 * <p>2. 回答后：把已经稳定的历史轮次增量压缩进长期摘要快照。</p>
 */
public interface ConversationMemoryService {

    /**
     * 读取当前会话可直接用于编排阶段的记忆上下文。
     */
    ConversationMemoryContext loadMemoryContext(String conversationId);

    /**
     * 读取当前会话记忆，并允许附带链路观测记录器。
     */
    default ConversationMemoryContext loadMemoryContext(String conversationId, ConversationTraceRecorder traceRecorder) {
        return loadMemoryContext(conversationId);
    }

    /**
     * 在一轮对话结束后，异步预热长期摘要。
     *
     * <p>即使这一步没有及时完成，下一轮回答前也会在 loadMemoryContext(...) 中同步自愈。</p>
     */
    void refreshConversationSummaryAsync(String conversationId);

    /**
     * 查询当前会话最新的长期摘要快照。
     */
    ConversationMemorySummaryView getConversationSummary(String conversationId);

    /**
     * 手动从当前会话历史重建长期摘要。
     *
     * <p>这个入口主要面向后台观测和教学演示，
     * 不依赖“下一轮对话到来时再顺手更新”，而是显式触发一次重算。</p>
     */
    ConversationMemorySummaryView rebuildConversationSummary(String conversationId);

    /**
     * 删除某个会话关联的长期摘要快照。
     */
    void deleteConversationSummary(String conversationId);
}
