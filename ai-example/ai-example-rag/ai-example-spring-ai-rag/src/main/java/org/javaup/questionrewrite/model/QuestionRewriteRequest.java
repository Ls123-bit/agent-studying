package org.javaup.questionrewrite.model;

import java.util.List;

/**
 * 问题改写示例请求体。
 */
public record QuestionRewriteRequest(
    String question,
    List<QuestionRewriteChatTurn> history,
    String chatModel,
    String targetSearchSystem,
    Integer topK,
    Double similarityThreshold
) {

    public List<QuestionRewriteChatTurn> safeHistory() {
        return history == null ? List.of() : history;
    }
}
