package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档问答模式下的检索规划结果。
 *
 * <p>它把“受约束改写结果”和“最终检索计划”一起返回，
 * 让编排层可以同时保留：</p>
 * <p>1. rewrite 阶段最终产出的表达层结果。</p>
 * <p>2. intent / anchor 共同决定的检索执行计划。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationRetrievalPlanningResult {

    /**
     * 受约束 rewrite 的最终结果。
     */
    private RagRewriteResult rewriteResult;

    /**
     * 当前轮会话关系/检索意图的结构化结果。
     */
    private ConversationIntentResolution intentResolution;

    /**
     * 最终检索锚点与检索计划结果。
     */
    private RetrievalAnchorResolution anchorResolution;

    /**
     * 当前轮导航状态快照。
     */
    private ConversationNavigationState navigationState;
}
