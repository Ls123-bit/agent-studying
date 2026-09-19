package org.javaup.ai.chatagent.rag.retrieve.channel;

import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;

/*
检索通道 接口
 */
public interface RetrievalChannel {

    String channelName();//通道名称标识

    boolean supports(ConversationExecutionPlan plan);//当前计划是否支持此通道

    RetrievalChannelResult retrieve(String subQuestion, ConversationExecutionPlan plan);//执行检索
}
