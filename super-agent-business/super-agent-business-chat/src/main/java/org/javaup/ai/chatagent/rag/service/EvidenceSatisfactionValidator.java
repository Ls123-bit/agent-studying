package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.rag.model.DocumentEvidencePolicy;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.javaup.ai.chatagent.rag.model.NavigationScopeMode;
import org.javaup.ai.chatagent.rag.model.SubQuestionEvidence;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 证据满足度校验器。
 *
 * <p>校验检索证据是否满足导航目标。
 * 不满足时直接标记为 unsatisfied，不允许进入 Prompt。</p>
 *
 * <p>规则：</p>
 * <ul>
 *   <li>HARD_SECTION: evidence.sectionPath 必须等于或位于 target.sectionPath 下</li>
 *   <li>HARD_ITEM: evidence.itemIndex 必须命中目标 item</li>
 *   <li>HARD_PARENT_WITH_SIBLINGS: evidence 必须覆盖当前 section 或同父兄弟</li>
 *   <li>MISSING_TARGET_STRUCTURE: 直接 no evidence</li>
 * </ul>
 */
@Slf4j
@Component
public class EvidenceSatisfactionValidator {

    /**
     * 校验并过滤证据。返回满足结构目标的证据子集。
     */
    public EvidenceSatisfactionResult validate(DocumentNavigationDecision decision,
                                                List<SubQuestionEvidence> evidenceList) {
        if (decision == null || evidenceList == null || evidenceList.isEmpty()) {
            return EvidenceSatisfactionResult.empty();
        }
        if (decision.isMissingRequestedStructure()) {
            log.info("证据校验: 目标结构不存在，直接 no evidence。");
            return EvidenceSatisfactionResult.rejected("目标章节在文档结构树中不存在，无法提供可靠证据。");
        }
        DocumentEvidencePolicy policy = decision.getEvidencePolicy();
        if (policy == null || !policy.isTargetStructureRequired()) {
            return EvidenceSatisfactionResult.accepted(evidenceList);
        }
        String targetSectionHint = decision.getStructureAnchor() == null
            ? ""
            : StrUtil.blankToDefault(decision.getStructureAnchor().getTargetSectionHint(), "");
        NavigationScopeMode scopeMode = decision.getStructureAnchor() == null
            ? NavigationScopeMode.NONE
            : NavigationScopeMode.valueOf(
                StrUtil.blankToDefault(decision.getStructureAnchor().getScopeMode(), "NONE"));

        List<SubQuestionEvidence> filteredList = new ArrayList<>();
        int totalRefs = 0;
        int satisfiedRefs = 0;

        for (SubQuestionEvidence evidence : evidenceList) {
            List<SearchReference> satisfiedReferences = new ArrayList<>();
            if (evidence.getReferences() != null) {
                for (SearchReference ref : evidence.getReferences()) {
                    totalRefs++;
                    if (referenceMatchesTarget(ref, targetSectionHint, scopeMode, policy, decision)) {
                        satisfiedReferences.add(ref);
                        satisfiedRefs++;
                    }
                }
            }
            if (!satisfiedReferences.isEmpty()) {
                SubQuestionEvidence filtered = new SubQuestionEvidence();
                filtered.setSubQuestionIndex(evidence.getSubQuestionIndex());
                filtered.setSubQuestion(evidence.getSubQuestion());
                filtered.setReferences(satisfiedReferences);
                filtered.setDocuments(evidence.getDocuments());
                filtered.setChannelTraces(evidence.getChannelTraces());
                filtered.setFusedCandidateCount(evidence.getFusedCandidateCount());
                filtered.setParentCandidateCount(evidence.getParentCandidateCount());
                filtered.setRerankedCandidateCount(evidence.getRerankedCandidateCount());
                filteredList.add(filtered);
            }
        }

        log.info("证据校验: targetSection='{}', scopeMode={}, totalRefs={}, satisfiedRefs={}",
            targetSectionHint, scopeMode, totalRefs, satisfiedRefs);

        if (filteredList.isEmpty()) {
            return EvidenceSatisfactionResult.rejected(
                "检索到的证据不属于目标章节「" + targetSectionHint + "」，无法给出可靠结论。");
        }
        return EvidenceSatisfactionResult.accepted(filteredList);
    }

    private boolean referenceMatchesTarget(SearchReference ref,
                                            String targetSectionHint,
                                            NavigationScopeMode scopeMode,
                                            DocumentEvidencePolicy policy,
                                            DocumentNavigationDecision decision) {
        if (ref == null) {
            return false;
        }
        String refSectionPath = StrUtil.blankToDefault(ref.getSectionPath(), "").trim().toLowerCase();
        String normalizedTarget = targetSectionHint.trim().toLowerCase();

        if (scopeMode == NavigationScopeMode.HARD_ITEM && policy.isExactItemRequired()) {
            if (decision.getStrictItemIndexes() != null && !decision.getStrictItemIndexes().isEmpty()) {
                Integer refItemIndex = ref.getItemIndex();
                if (refItemIndex == null || !decision.getStrictItemIndexes().contains(refItemIndex)) {
                    return false;
                }
            }
        }

        if (scopeMode == NavigationScopeMode.HARD_SECTION || scopeMode == NavigationScopeMode.HARD_ITEM) {
            if (StrUtil.isBlank(normalizedTarget)) {
                return true;
            }
            return refSectionPath.contains(normalizedTarget) || normalizedTarget.contains(refSectionPath);
        }

        if (scopeMode == NavigationScopeMode.HARD_PARENT_WITH_SIBLINGS) {
            if (StrUtil.isBlank(normalizedTarget)) {
                return true;
            }
            return refSectionPath.contains(normalizedTarget) || normalizedTarget.contains(refSectionPath);
        }

        return true;
    }

    /**
     * 证据校验结果。
     */
    public record EvidenceSatisfactionResult(
        boolean satisfied,
        List<SubQuestionEvidence> filteredEvidence,
        String rejectionReason
    ) {
        public static EvidenceSatisfactionResult empty() {
            return new EvidenceSatisfactionResult(false, List.of(), "无证据。");
        }

        public static EvidenceSatisfactionResult accepted(List<SubQuestionEvidence> evidence) {
            return new EvidenceSatisfactionResult(true, evidence, "");
        }

        public static EvidenceSatisfactionResult rejected(String reason) {
            return new EvidenceSatisfactionResult(false, List.of(), reason);
        }
    }
}
