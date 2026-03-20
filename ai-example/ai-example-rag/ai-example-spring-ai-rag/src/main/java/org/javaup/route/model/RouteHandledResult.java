package org.javaup.route.model;

import java.util.List;

/**
 * 单条路由执行后的结果。
 */
public class RouteHandledResult {

    private final String routeName;
    private final String answer;
    private final String standaloneQuestion;
    private final List<String> references;

    public RouteHandledResult(String routeName, String answer, String standaloneQuestion, List<String> references) {
        this.routeName = routeName == null ? "" : routeName;
        this.answer = answer == null ? "" : answer;
        this.standaloneQuestion = standaloneQuestion == null ? "" : standaloneQuestion;
        this.references = references == null ? List.of() : List.copyOf(references);
    }

    public String getRouteName() {
        return routeName;
    }

    public String getAnswer() {
        return answer;
    }

    public String getStandaloneQuestion() {
        return standaloneQuestion;
    }

    public List<String> getReferences() {
        return references;
    }
}
