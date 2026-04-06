package org.javaup.ai.manage.service;

import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.javaup.ai.manage.support.DocumentAnalysisResult;
import org.javaup.ai.manage.support.DocumentStrategyPlanDraft;
import org.javaup.ai.manage.support.ParentBlockCandidate;

import java.util.List;

/**
 * 文档策略服务。
 */
public interface DocumentStrategyService {

    /**
     * 根据文档分析结果推荐策略方案。
     */
    DocumentStrategyPlanDraft recommendStrategy(SuperAgentDocument document, DocumentAnalysisResult analysisResult);

    /**
     * 对用户提交的策略做标准化处理。
     */
    List<SuperAgentDocumentStrategyStep> normalizeSteps(SuperAgentDocumentStrategyPlan basePlan,
                                                        List<SuperAgentDocumentStrategyStep> baseSteps,
                                                        List<Integer> requestStrategyTypes,
                                                        Long documentId);

    /**
     * 执行 Parent-Child 版切块流水线。
     *
     * <p>这里不再直接返回一串平铺 child chunk，
     * 而是先产出稳定的 parent block，
     * 再在每个 parent 内部派生出 child chunk。</p>
     */
    List<ParentBlockCandidate> buildParentBlocks(SuperAgentDocument document,
                                                 SuperAgentDocumentStrategyPlan plan,
                                                 List<SuperAgentDocumentStrategyStep> steps,
                                                 String parsedText);
}
