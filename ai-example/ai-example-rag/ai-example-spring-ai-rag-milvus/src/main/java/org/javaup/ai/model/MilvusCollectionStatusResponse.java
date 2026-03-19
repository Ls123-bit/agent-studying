package org.javaup.ai.model;

import java.util.List;
import java.util.Map;

public record MilvusCollectionStatusResponse(
        String uri,
        String databaseName,
        String collectionName,
        boolean exists,
        String loadState,
        Map<String, String> statistics,
        List<MilvusIndexInfo> indexes,
        String collectionSummary
) {
}
