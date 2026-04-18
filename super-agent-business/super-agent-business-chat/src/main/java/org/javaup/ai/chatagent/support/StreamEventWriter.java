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
@Component
public class StreamEventWriter {

    private final ObjectMapper objectMapper;

    public StreamEventWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String text(String content) {
        return text(content, null);
    }

    public String text(String content, StreamEventMetadata metadata) {
        /*
         * text 是最核心的流式事件类型：
         * 前端会把它持续拼接到当前这条 assistant 消息的正文里。
         */
        return write(event("text", content, metadata));
    }

    public String thinking(String content) {
        return thinking(content, null);
    }

    public String thinking(String content, StreamEventMetadata metadata) {
        /*
         * thinking 代表过程提示，不直接并入最终正文，
         * 而是给前端单独展示“正在搜索/正在分析”这类过程信息。
         */
        return write(event("thinking", content, metadata));
    }

    public String status(String content) {
        return status(content, null);
    }

    public String status(String content, StreamEventMetadata metadata) {
        /*
         * status 更偏“状态横幅”语义，例如停止生成、搜索完成等。
         */
        return write(event("status", content, metadata));
    }

    public String error(String content) {
        return error(content, null);
    }

    public String error(String content, StreamEventMetadata metadata) {
        /*
         * error 会在前端被映射成失败态，而不是正文文本。
         * 这样前端可以把失败提示和回答内容明显区分开。
         */
        return write(event("error", content, metadata));
    }

    public String references(List<SearchReference> references) {
        return references(references, null);
    }

    public String references(List<SearchReference> references, StreamEventMetadata metadata) {
        /*
         * 引用来源除了具体内容，还额外补一个 count，
         * 方便前端直接显示“共找到多少条来源”而不用自己再数一遍。
         */
        Map<String, Object> payload = event("reference", references, metadata);
        payload.put("count", references != null ? references.size() : 0);
        return write(payload);
    }

    public String recommendations(List<String> recommendations) {
        return recommendations(recommendations, null);
    }

    public String recommendations(List<String> recommendations, StreamEventMetadata metadata) {
        /*
         * 推荐问题和引用来源一样，统一补充数量字段，方便前端展示和调试。
         */
        Map<String, Object> payload = event("recommend", recommendations, metadata);
        payload.put("count", recommendations != null ? recommendations.size() : 0);
        return write(payload);
    }

    private Map<String, Object> event(String type, Object content, StreamEventMetadata metadata) {
        /*
         * 所有 SSE 事件都走统一信封结构：
         * type 用来区分事件类型，content 放业务内容，timestamp 记录服务端发包时间。
         */
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("content", content);
        payload.put("timestamp", Instant.now().toString());
        if (metadata != null) {
            /*
             * conversationId / exchangeId 不属于每类事件的业务内容本身，
             * 但属于所有事件都共享的“会话定位信息”。
             *
             * 把它们放在统一信封层而不是各自 content 里，
             * 前端就能在不理解具体事件结构的前提下，始终准确把事件归到当前 assistant turn。
             */
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
