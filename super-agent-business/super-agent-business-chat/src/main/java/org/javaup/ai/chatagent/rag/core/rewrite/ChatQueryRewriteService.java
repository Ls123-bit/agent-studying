package org.javaup.ai.chatagent.rag.core.rewrite;

import org.javaup.ai.chatagent.model.ConversationExchangeView;

import java.util.List;

/**
 * 查询改写服务接口。
 *
 * <p>负责将用户的原始问题改写为独立、完整、适合检索的形式，
 * 并在需要时拆分为多个子问题。</p>
 *
 * <p>设计原则：改写阶段完全独立，不依赖意图分类或导航等上游结果，
 * 避免上游错误向下传播。</p>
 */
public interface ChatQueryRewriteService {

    /**
     * 基于对话历史改写并拆分用户问题。
     *
     * @param question      用户原始问题
     * @param recentHistory 最近的对话交换记录（用于指代消解和上下文补全）
     * @return 改写结果，包含改写后的问题和子问题列表
     */
    RewriteResult rewriteWithSplit(String question, List<ConversationExchangeView> recentHistory);
}
