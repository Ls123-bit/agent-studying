package org.javaup.ai.chatagent.support;


/*
SSE事件元数据
 */
public record StreamEventMetadata(
    String conversationId,// 会话ID
    Long exchangeId//轮次ID
) {
}
