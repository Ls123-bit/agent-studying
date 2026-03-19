package org.javaup.questionrewrite.model;

/**
 * 一条对话历史记录。
 */
public record QuestionRewriteChatTurn(
    String role,
    String content
) {
}
