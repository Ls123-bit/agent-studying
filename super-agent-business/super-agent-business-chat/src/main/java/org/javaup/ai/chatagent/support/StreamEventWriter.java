package org.javaup.ai.chatagent.support;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.chatagent.model.SearchReference;
import org.springframework.stereotype.Component;

@Component
public class StreamEventWriter {

    private final ObjectMapper objectMapper;

    public StreamEventWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String text(String content) {
        return write(event("text", content));
    }

    public String thinking(String content) {
        return write(event("thinking", content));
    }

    public String status(String content) {
        return write(event("status", content));
    }

    public String error(String content) {
        return write(event("error", content));
    }

    public String references(List<SearchReference> references) {
        /*
         * 引用来源除了具体内容，还额外补一个 count，
         * 方便前端直接显示“共找到多少条来源”而不用自己再数一遍。
         */
        Map<String, Object> payload = event("reference", references);
        payload.put("count", references != null ? references.size() : 0);
        return write(payload);
    }

    public String recommendations(List<String> recommendations) {
        /*
         * 推荐问题和引用来源一样，统一补充数量字段，方便前端展示和调试。
         */
        Map<String, Object> payload = event("recommend", recommendations);
        payload.put("count", recommendations != null ? recommendations.size() : 0);
        return write(payload);
    }

    private Map<String, Object> event(String type, Object content) {
        /*
         * 所有 SSE 事件都走统一信封结构：
         * type 用来区分事件类型，content 放业务内容，timestamp 记录服务端发包时间。
         */
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("content", content);
        payload.put("timestamp", Instant.now().toString());
        return payload;
    }

    private String write(Map<String, Object> payload) {
        /*
         * 底层 sink 传递的是字符串，因此这里统一在出口做 JSON 序列化。
         */
        try {
            return objectMapper.writeValueAsString(payload);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("流式事件序列化失败", exception);
        }
    }
}
