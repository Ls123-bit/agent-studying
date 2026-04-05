package org.javaup.ai.chatagent.support;

/**
 * 流式事件的会话元数据。
 */
public record StreamEventMetadata(
    String conversationId,
    Long exchangeId
) {
}
