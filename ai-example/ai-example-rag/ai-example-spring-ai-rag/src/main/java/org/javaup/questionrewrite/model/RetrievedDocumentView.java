package org.javaup.questionrewrite.model;

import java.util.Map;

/**
 * 返回给前端的检索文档视图。
 */
public record RetrievedDocumentView(
    String id,
    Double score,
    String text,
    Map<String, Object> metadata
) {
}
