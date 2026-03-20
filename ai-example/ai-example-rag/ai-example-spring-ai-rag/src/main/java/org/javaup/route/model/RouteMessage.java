package org.javaup.route.model;

import org.springframework.util.StringUtils;

/**
 * 轻量会话消息。
 */
public class RouteMessage {

    private final String role;
    private final String content;

    public RouteMessage(String role, String content) {
        this.role = StringUtils.hasText(role) ? role.trim() : "user";
        this.content = StringUtils.hasText(content) ? content.trim() : "";
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }
}
