package org.javaup.ai.chatagent.rag.retrieve.channel;

import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 检索通道抽象
 * @author: 阿星不是程序员
 **/
/*
检索通道 接口
 */
public interface RetrievalChannel {

    String channelName();//通道名称标识

    boolean supports(ConversationExecutionPlan plan);//当前计划是否支持此通道

    RetrievalChannelResult retrieve(String subQuestion, ConversationExecutionPlan plan);//执行检索
}
