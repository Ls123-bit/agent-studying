package org.javaup.ai.chatagent.service;

import org.javaup.ai.chatagent.model.ChannelExecutionView;
import org.javaup.ai.chatagent.model.RetrievalResultView;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
/**
 * 检索观测数据存储接口。
 */
public interface RetrievalObserveStore {

    /**
     * 批量保存检索结果。
     */
    void batchSaveResults(String conversationId, long exchangeId, List<RetrievalResultView> results);

    /**
     * 批量保存通道执行记录。
     */
    void batchSaveChannelExecutions(String conversationId, long exchangeId, List<ChannelExecutionView> executions);

    /**
     * 查询检索结果列表。
     */
    List<RetrievalResultView> listResults(String conversationId, long exchangeId);

    /**
     * 查询通道执行列表。
     */
    List<ChannelExecutionView> listChannelExecutions(String conversationId, long exchangeId);

    /**
     * 删除会话的所有观测数据。
     */
    void deleteByConversation(String conversationId);
}
