package org.javaup.ai.manage.service;

import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.javaup.ai.manage.support.ChunkCandidate;
import org.javaup.ai.manage.support.DocumentAnalysisResult;
import org.javaup.ai.manage.support.DocumentStrategyPlanDraft;

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
     * 执行切块流水线。
     */
    List<ChunkCandidate> buildChunks(SuperAgentDocument document,
                                     SuperAgentDocumentStrategyPlan plan,
                                     List<SuperAgentDocumentStrategyStep> steps,
                                     String parsedText);
}
