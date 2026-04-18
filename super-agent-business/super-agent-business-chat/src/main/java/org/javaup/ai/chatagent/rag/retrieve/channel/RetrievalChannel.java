package org.javaup.ai.chatagent.rag.retrieve.channel;

import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 检索通道抽象
 * @author: 阿星不是程序员
 **/
/**
 * 检索通道抽象。
 *
 * <p>第二期的目标不是单纯“功能上同时做向量和关键词检索”，
 * 而是把不同检索路径提升成统一可插拔接口，
 * 这样后续加 WebSearchChannel、元数据过滤通道或其他召回通道时，
 * 都不需要回头改主引擎的控制流。</p>
 */
public interface RetrievalChannel {

    /**
     * 通道名称。
     */
    String channelName();

    /**
     * 当前计划下该通道是否可用。
     */
    boolean supports(ConversationExecutionPlan plan);

    /**
     * 执行当前通道检索。
     */
    RetrievalChannelResult retrieve(String subQuestion, ConversationExecutionPlan plan);
}
