package org.javaup.ai.chatagent.tool;

public record TavilySearchRequest(
    String query,
    String topic,
    Integer maxResults
) {
}
