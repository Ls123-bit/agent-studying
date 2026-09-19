package org.javaup.ai.manage.service;

import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.javaup.ai.manage.support.DocumentAnalysisResult;
import org.javaup.ai.manage.support.DocumentStrategyPlanDraft;
import org.javaup.ai.manage.support.ParentBlockCandidate;

import java.util.List;

/*
分块策略
 */
public interface DocumentStrategyService {
//根据文档特征推荐最佳分块策略
    DocumentStrategyPlanDraft recommendStrategy(SuperAgentDocument document, DocumentAnalysisResult analysisResult);
//标准化策略步骤（用户调整后重新计算）
    List<SuperAgentDocumentStrategyStep> normalizeSteps(SuperAgentDocumentStrategyPlan basePlan,
                                                        List<SuperAgentDocumentStrategyStep> baseSteps,
                                                        List<Integer> requestParentStrategyTypes,
                                                        List<Integer> requestChildStrategyTypes,
                                                        Long documentId);
//执行分块：先切父块，再切子块
    List<ParentBlockCandidate> buildParentBlocks(SuperAgentDocument document,
                                                 SuperAgentDocumentStrategyPlan plan,
                                                 List<SuperAgentDocumentStrategyStep> steps,
                                                 String parsedText);
}
