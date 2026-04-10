package org.javaup.ai.chatagent.rag.model;

/**
 * 当前问题期望的答案形态。
 *
 * <p>它表达的是“最终回答更像什么”，
 * 不是检索通道或 Prompt 细节本身。</p>
 */
public enum ConversationAnswerShape {
    /**
     * 列举型回答。
     */
    LIST,

    /**
     * 步骤型回答。
     */
    STEPS,

    /**
     * 目录/章节结构型回答。
     */
    OUTLINE,

    /**
     * 对比型回答。
     */
    COMPARISON,

    /**
     * 解释说明型回答。
     */
    EXPLANATION,

    /**
     * 判断/结论型回答。
     */
    JUDGMENT,

    /**
     * 单事实回答。
     */
    FACT,

    /**
     * 无法稳定判断。
     */
    UNKNOWN
}
