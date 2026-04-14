package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档问答模式下的检索规划结果。
 *
 * <p>它把“受约束改写结果”和“统一导航决策”一起返回，
 * 让编排层可以同时保留：</p>
 * <p>1. rewrite 阶段最终产出的表达层结果。</p>
 * <p>2. 导航内核统一产出的结构定位、检索计划与证据策略。</p>
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
     * 当前轮统一导航内核的最终决策。
     */
    private DocumentNavigationDecision navigationDecision;
}
