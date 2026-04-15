package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.chatagent.rag.core.guidance.GuidanceDecision;
import org.javaup.ai.chatagent.rag.core.intent.SubQuestionIntent;
import org.javaup.ai.chatagent.rag.core.rewrite.RewriteResult;

import java.util.List;

/**
 * 检索规划结果。
 *
 * <p>新架构下的规划结果包含三个独立阶段的产出：</p>
 * <ol>
 *   <li>独立改写结果 — 不依赖任何上游约束</li>
 *   <li>章节意图分类结果 — 软路由信号，不做硬锁定</li>
 *   <li>歧义检测结果 — 不确定时主动追问</li>
 * </ol>
 *
 * <p>与旧版的关键区别：移除了 ConversationIntentResolution 和 DocumentNavigationDecision，
 * 不再有硬性章节锁定和结构验证。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationRetrievalPlanningResult {

    /**
     * 独立改写结果（包含改写后的问题和子问题列表）。
     */
    private RewriteResult rewriteResult;

    /**
     * 每个子问题的章节意图分类结果。
     */
    private List<SubQuestionIntent> subQuestionIntents;

    /**
     * 歧义检测结果。
     */
    private GuidanceDecision guidanceDecision;
}
