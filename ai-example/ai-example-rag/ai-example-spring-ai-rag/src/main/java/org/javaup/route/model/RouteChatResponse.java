package org.javaup.route.model;

/**
 * 对外返回的完整路由响应。
 */
public class RouteChatResponse {

    private final String sessionId;
    private final String question;
    private final RouteIntent intent;
    private final String routeName;
    private final String answer;

    public RouteChatResponse(String sessionId,
                             String question,
                             RouteIntent intent,
                             String routeName,
                             String answer) {
        this.sessionId = sessionId;
        this.question = question;
        this.intent = intent;
        this.routeName = routeName;
        this.answer = answer;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getQuestion() {
        return question;
    }

    public RouteIntent getIntent() {
        return intent;
    }

    public String getRouteName() {
        return routeName;
    }

    public String getAnswer() {
        return answer;
    }
}
