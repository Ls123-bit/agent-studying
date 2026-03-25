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
        Map<String, Object> payload = event("reference", references);
        payload.put("count", references != null ? references.size() : 0);
        return write(payload);
    }

    public String recommendations(List<String> recommendations) {
        Map<String, Object> payload = event("recommend", recommendations);
        payload.put("count", recommendations != null ? recommendations.size() : 0);
        return write(payload);
    }

    private Map<String, Object> event(String type, Object content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("content", content);
        payload.put("timestamp", Instant.now().toString());
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
