package org.javaup.route.model;

import org.springframework.util.StringUtils;

/**
 * 意图识别结果。
 */
public class RouteDecision {

    private final RouteIntent intent;
    private final double confidence;
    private final String source;
    private final String reason;

    public RouteDecision(RouteIntent intent, double confidence, String source, String reason) {
        this.intent = intent == null ? RouteIntent.KNOWLEDGE : intent;
        this.confidence = Math.max(0.0d, Math.min(confidence, 1.0d));
        this.source = StringUtils.hasText(source) ? source : "fallback";
        this.reason = StringUtils.hasText(reason) ? reason : "未提供判定原因";
    }

    public RouteIntent getIntent() {
        return intent;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getSource() {
        return source;
    }

    public String getReason() {
        return reason;
    }
}
