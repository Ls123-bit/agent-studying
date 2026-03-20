package org.javaup.route.model;

import java.util.List;

/**
 * 对外返回的完整路由响应。
 */
public class RouteChatResponse {

    private final String sessionId;
    private final String question;
    private final String answer;
    private final String routeName;
    private final RouteIntent intent;
    private final double confidence;
    private final String decisionSource;
    private final String reason;
    private final String standaloneQuestion;
    private final List<String> references;
    private final List<RouteMessage> historyUsed;

    public RouteChatResponse(String sessionId,
                             String question,
                             String answer,
                             String routeName,
                             RouteIntent intent,
                             double confidence,
                             String decisionSource,
                             String reason,
                             String standaloneQuestion,
                             List<String> references,
                             List<RouteMessage> historyUsed) {
        this.sessionId = sessionId;
        this.question = question;
        this.answer = answer;
        this.routeName = routeName;
        this.intent = intent;
        this.confidence = confidence;
        this.decisionSource = decisionSource;
        this.reason = reason;
        this.standaloneQuestion = standaloneQuestion;
        this.references = references == null ? List.of() : List.copyOf(references);
        this.historyUsed = historyUsed == null ? List.of() : List.copyOf(historyUsed);
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getQuestion() {
        return question;
    }

    public String getAnswer() {
        return answer;
    }

    public String getRouteName() {
        return routeName;
    }

    public RouteIntent getIntent() {
        return intent;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getDecisionSource() {
        return decisionSource;
    }

    public String getReason() {
        return reason;
    }

    public String getStandaloneQuestion() {
        return standaloneQuestion;
    }

    public List<String> getReferences() {
        return references;
    }

    public List<RouteMessage> getHistoryUsed() {
        return historyUsed;
    }
}
