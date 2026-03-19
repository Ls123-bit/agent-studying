package org.javaup.questionrewrite.model;

import java.util.List;

/**
 * 问题改写预览返回值。
 */
public record QuestionRewritePreviewResponse(
    String originalQuestion,
    String rewrittenQuestion,
    String chatModel,
    String targetSearchSystem,
    List<RetrievedDocumentView> retrievedDocuments
) {
}
