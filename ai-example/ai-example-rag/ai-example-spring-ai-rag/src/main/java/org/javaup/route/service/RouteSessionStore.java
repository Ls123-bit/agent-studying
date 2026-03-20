package org.javaup.route.service;

import org.javaup.route.model.RouteMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 最简单的内存会话存储。
 * 这里只是为了示例方便，真实项目里通常会接 Redis、数据库或专门的会话存储。
 */
@Component
public class RouteSessionStore {

    private static final String DEFAULT_SESSION_ID = "route-demo-session";

    private final Map<String, CopyOnWriteArrayList<RouteMessage>> sessions = new ConcurrentHashMap<>();

    /**
     * 返回历史副本，避免外部直接改内部存储。
     */
    public List<RouteMessage> getHistory(String sessionId) {
        return new ArrayList<>(sessions.getOrDefault(sessionId, new CopyOnWriteArrayList<>()));
    }

    /**
     * 追加用户消息。
     */
    public void appendUser(String sessionId, String question) {
        append(normalizeSessionId(sessionId), new RouteMessage("user", question));
    }

    /**
     * 追加助手消息。
     */
    public void appendAssistant(String sessionId, String answer) {
        append(normalizeSessionId(sessionId), new RouteMessage("assistant", answer));
    }

    /**
     * 清空整段会话。
     */
    public void clear(String sessionId) {
        sessions.remove(normalizeSessionId(sessionId));
    }

    /**
     * 统一兜底 sessionId，避免外部没传时出现空 key。
     */
    public String normalizeSessionId(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId.trim() : DEFAULT_SESSION_ID;
    }

    private void append(String sessionId, RouteMessage message) {
        // CopyOnWriteArrayList 足够覆盖示例场景，代码简单，也方便并发读。
        sessions.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>()).add(message);
    }
}
