package org.javaup.ai.model;

import java.util.Map;

public record MilvusSearchResult(
        String id,
        String content,
        Double score,
        String docId,
        String category,
        Map<String, Object> metadata
) {
}
