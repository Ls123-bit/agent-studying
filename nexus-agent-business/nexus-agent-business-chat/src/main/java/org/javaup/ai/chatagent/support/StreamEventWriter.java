package org.javaup.ai.chatagent.support;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.chatagent.model.SearchReference;
import org.springframework.stereotype.Component;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/

/*
SSE时间输出
负责把各种事件格式化成 JSON 推送给前端。
每个方法都有带 StreamEventMetadata 参数的重载版本，用于附加 conversationId 和 exchangeId。
 */
@Component
public class StreamEventWriter {

    private final ObjectMapper objectMapper;

    public StreamEventWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String text(String content) {//模型输出的文本片段    事件类型：text
        return text(content, null);
    }

    public String text(String content, StreamEventMetadata metadata) {

        return write(event("text", content, metadata));
    }

    public String thinking(String content) {//思考过程    事件类型：thinking
        return thinking(content, null);
    }

    public String thinking(String content, StreamEventMetadata metadata) {

        return write(event("thinking", content, metadata));
    }

    public String status(String content) {//状态变更（如"正在检索"）    事件类型：status
        return status(content, null);
    }

    public String status(String content, StreamEventMetadata metadata) {

        return write(event("status", content, metadata));
    }

    public String error(String content) {//错误信息    事件类型：error
        return error(content, null);
    }

    public String error(String content, StreamEventMetadata metadata) {

        return write(event("error", content, metadata));
    }

    public String references(List<SearchReference> references) {//引用来源列表    事件类型：reference
        return references(references, null);
    }

    public String references(List<SearchReference> references, StreamEventMetadata metadata) {

        Map<String, Object> payload = event("reference", references, metadata);
        payload.put("count", references != null ? references.size() : 0);
        return write(payload);
    }

    public String recommendations(List<String> recommendations) {//推荐的后续问题    事件类型：recommend
        return recommendations(recommendations, null);
    }

    public String recommendations(List<String> recommendations, StreamEventMetadata metadata) {

        Map<String, Object> payload = event("recommend", recommendations, metadata);
        payload.put("count", recommendations != null ? recommendations.size() : 0);
        return write(payload);
    }

    private Map<String, Object> event(String type, Object content, StreamEventMetadata metadata) {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("content", content);
        payload.put("timestamp", Instant.now().toString());
        if (metadata != null) {

            if (metadata.conversationId() != null && !metadata.conversationId().isBlank()) {
                payload.put("conversationId", metadata.conversationId());
            }
            if (metadata.exchangeId() != null && metadata.exchangeId() > 0) {
                payload.put("exchangeId", metadata.exchangeId());
            }
        }
        return payload;
    }

    private String write(Map<String, Object> payload) {

        try {
            return objectMapper.writeValueAsString(payload);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("流式事件序列化失败", exception);
        }
    }
}
