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
        /*
         * text 是最核心的流式事件类型：
         * 前端会把它持续拼接到当前这条 assistant 消息的正文里。
         */
        return write(event("text", content));
    }

    public String thinking(String content) {
        /*
         * thinking 代表过程提示，不直接并入最终正文，
         * 而是给前端单独展示“正在搜索/正在分析”这类过程信息。
         */
        return write(event("thinking", content));
    }

    public String status(String content) {
        /*
         * status 更偏“状态横幅”语义，例如停止生成、搜索完成等。
         */
        return write(event("status", content));
    }

    public String error(String content) {
        /*
         * error 会在前端被映射成失败态，而不是正文文本。
         * 这样前端可以把失败提示和回答内容明显区分开。
         */
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
         *
         * 统一从这里出站还有一个好处：
         * 不同类型的事件都共用同一套信封结构，
         * 前端消费 SSE 时只需要先解析 JSON，再按 type 分发即可。
         */
        try {
            return objectMapper.writeValueAsString(payload);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("流式事件序列化失败", exception);
        }
    }
}
