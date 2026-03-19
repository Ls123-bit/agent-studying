package org.javaup.questionrewrite.model;

import java.util.List;

/**
 * 完整问答链路返回值。
 */
public record QuestionRewriteAnswerResponse(
    String originalQuestion,
    String rewrittenQuestion,
    String answer,
    String chatModel,
    String targetSearchSystem,
    List<RetrievedDocumentView> retrievedDocuments
) {
}
