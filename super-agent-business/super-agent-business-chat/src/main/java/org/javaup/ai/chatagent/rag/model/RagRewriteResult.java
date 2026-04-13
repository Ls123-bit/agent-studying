package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 问题改写结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagRewriteResult {

    /**
     * 改写后的独立问题。
     */
    private String rewrittenQuestion;

    /**
     * 拆分后的子问题列表。
     */
    private List<String> subQuestions;

    /**
     * 模型原始输出（用于观测和调试）。
     */
    private String rawModelOutput;

    public RagRewriteResult(String rewrittenQuestion, List<String> subQuestions) {
        this.rewrittenQuestion = rewrittenQuestion;
        this.subQuestions = subQuestions;
    }
}
