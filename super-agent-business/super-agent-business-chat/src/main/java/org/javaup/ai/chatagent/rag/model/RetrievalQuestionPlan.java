package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 检索阶段真正执行的问题计划
 * @author: 阿星不是程序员
 **/
/**
 * 检索阶段真正执行的问题计划。
 *
 * <p>它和“问题改写结果”刻意分层：</p>
 * <p>1. RagRewriteResult 只表达改写阶段产出的独立问题和拆分结果。</p>
 * <p>2. RetrievalQuestionPlan 表达锚点承接之后，检索层最终要执行什么。</p>
 *
 * <p>这样可以避免把“锚点规划结果”继续伪装成“改写结果”，
 * 导致后续阶段误以为原始 rewrite 就长这样。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalQuestionPlan {

    /**
     * 最终真正用于检索的主问题。
     */
    private String retrievalQuestion;

    /**
     * 最终真正用于检索的子问题列表。
     */
    private List<String> subQuestions;
}
