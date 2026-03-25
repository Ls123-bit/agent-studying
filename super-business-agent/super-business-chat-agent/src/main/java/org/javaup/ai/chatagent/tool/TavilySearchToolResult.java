package org.javaup.ai.chatagent.tool;

import java.util.List;

import org.javaup.ai.chatagent.model.SearchReference;

public record TavilySearchToolResult(
    String query,
    String answer,
    List<SearchReference> results
) {
}
