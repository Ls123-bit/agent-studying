package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 多轮追问锚点解析结果。
 *
 * <p>它把“检索阶段真正要执行的问题计划”和“检索锚点状态”放在一起返回，
 * 让前置编排层可以明确看到：</p>
 * <p>1. 最终真正用于检索的问题和子问题是什么。</p>
 * <p>2. 当前轮是否应用了会话锚点。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalAnchorResolution {

    /**
     * 最终真正用于检索的问题计划。
     */
    private RetrievalQuestionPlan retrievalPlan;

    /**
     * 当前轮检索锚点上下文。
     */
    private RetrievalAnchorContext anchorContext;
}
