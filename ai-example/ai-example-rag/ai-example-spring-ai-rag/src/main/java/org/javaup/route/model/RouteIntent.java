package org.javaup.route.model;

import java.util.Locale;

/**
 * 路由意图枚举。
 */
public enum RouteIntent {

    KNOWLEDGE("knowledge", "知识检索"),
    TOOL("tool", "工具调用"),
    CHITCHAT("chitchat", "闲聊寒暄"),
    CLARIFY("clarify", "引导澄清");

    private final String code;
    private final String label;

    RouteIntent(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static RouteIntent from(String value) {
        if (value == null) {
            return KNOWLEDGE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "tool" -> TOOL;
            case "chitchat" -> CHITCHAT;
            case "clarify", "clarification" -> CLARIFY;
            default -> KNOWLEDGE;
        };
    }
}
