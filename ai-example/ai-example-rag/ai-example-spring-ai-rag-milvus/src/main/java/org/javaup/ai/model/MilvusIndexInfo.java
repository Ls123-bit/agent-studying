package org.javaup.ai.model;

import java.util.Map;

public record MilvusIndexInfo(
        String fieldName,
        String indexName,
        String state,
        long indexedRows,
        long totalRows,
        Map<String, String> params
) {
}
