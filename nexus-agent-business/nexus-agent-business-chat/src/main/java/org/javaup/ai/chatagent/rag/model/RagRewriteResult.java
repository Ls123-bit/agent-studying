package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;


@Data
@NoArgsConstructor
@AllArgsConstructor
/**
 * 数据模型
 */
public class RagRewriteResult {

    private String rewrittenQuestion;//改写后的检索友好问题

    private List<String> subQuestions;//拆分出的子问题列表

    private String rawModelOutput;//LLM 原始输出（调试用）


    public RagRewriteResult(String rewrittenQuestion, List<String> subQuestions) {
        this.rewrittenQuestion = rewrittenQuestion;
        this.subQuestions = subQuestions;
    }
}
