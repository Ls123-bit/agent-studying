package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.model.ConversationIntentRelationType;
import org.javaup.ai.chatagent.rag.model.ConversationIntentResolution;
import org.javaup.ai.chatagent.rag.model.ConversationItemAnchor;
import org.javaup.ai.chatagent.rag.model.ConversationNavigationState;
import org.javaup.ai.chatagent.rag.model.ConversationStructureAnchor;
import org.javaup.ai.chatagent.rag.model.ConversationSubjectAnchor;
import org.javaup.ai.chatagent.rag.model.ConversationTopicAnchor;
import org.javaup.ai.chatagent.rag.model.RetrievalAnchorContext;
import org.javaup.ai.chatagent.rag.model.RetrievalAnchorResolution;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话导航服务。
 *
 * <p>这一层不直接负责检索，也不直接生成回答，
 * 它只负责把当前轮问题压缩成四层锚点状态：</p>
 * <p>1. subject：围绕谁/什么对象</p>
 * <p>2. topic：当前问哪类信息</p>
 * <p>3. structure：当前最可能落到哪个结构范围</p>
 * <p>4. item：当前是否在追某个编号项</p>
 */
@Service
public class ConversationNavigationService {

    /**
     * 基于语义规划结果和检索锚点结果生成导航状态。
     */
    public ConversationNavigationState resolve(String question,
                                               ConversationIntentResolution intentResolution,
                                               RetrievalAnchorResolution anchorResolution) {
        RetrievalAnchorContext anchorContext = anchorResolution == null ? null : anchorResolution.getAnchorContext();

        ConversationSubjectAnchor subjectAnchor = buildSubjectAnchor(question, intentResolution, anchorContext);
        ConversationTopicAnchor topicAnchor = buildTopicAnchor(intentResolution, anchorContext);
        ConversationStructureAnchor structureAnchor = buildStructureAnchor(anchorContext);
        ConversationItemAnchor itemAnchor = buildItemAnchor(anchorContext);

        return ConversationNavigationState.builder()
            .relationType(intentResolution == null ? ConversationIntentRelationType.UNKNOWN : intentResolution.getRelationType())
            .retrievalMode(intentResolution == null ? null : intentResolution.getRetrievalMode())
            .subjectAnchor(subjectAnchor)
            .topicAnchor(topicAnchor)
            .structureAnchor(structureAnchor)
            .itemAnchor(itemAnchor)
            .summaryText(buildSummary(subjectAnchor, topicAnchor, structureAnchor, itemAnchor))
            .build();
    }

    private ConversationSubjectAnchor buildSubjectAnchor(String question,
                                                         ConversationIntentResolution intentResolution,
                                                         RetrievalAnchorContext anchorContext) {
        String subjectText = firstNonBlank(
            deriveSubjectFromIntent(intentResolution),
            deriveSubjectFromAnchor(anchorContext),
            safeText(question)
        );
        boolean inherited = intentResolution != null && intentResolution.getRelationType() == ConversationIntentRelationType.FOLLOW_UP;
        return ConversationSubjectAnchor.builder()
            .anchorText(subjectText)
            .source(resolveSubjectSource(intentResolution, anchorContext))
            .inherited(inherited)
            .build();
    }

    private ConversationTopicAnchor buildTopicAnchor(ConversationIntentResolution intentResolution,
                                                     RetrievalAnchorContext anchorContext) {
        String facet = safeText(intentResolution == null ? "" : intentResolution.getResolvedFacet());
        String topicText = firstNonBlank(
            facet,
            safeText(anchorContext == null ? "" : anchorContext.getTargetFacet()),
            deriveTopicFromIntent(intentResolution)
        );
        return ConversationTopicAnchor.builder()
            .anchorText(topicText)
            .facet(facet)
            .informationNeed(safeText(intentResolution == null ? "" : intentResolution.getInformationNeed()))
            .build();
    }

    private ConversationStructureAnchor buildStructureAnchor(RetrievalAnchorContext anchorContext) {
        if (anchorContext == null) {
            return ConversationStructureAnchor.builder().scopeMode("NONE").build();
        }
        String canonicalPath = firstCanonicalPath(anchorContext);
        Long structureNodeId = firstStructureNodeId(anchorContext);
        String scopeMode = "NONE";
        if (structureNodeId != null
            || StrUtil.isNotBlank(canonicalPath)
            || (anchorContext.getStrictSectionHints() != null && !anchorContext.getStrictSectionHints().isEmpty())) {
            scopeMode = "HARD";
        }
        else if (StrUtil.isNotBlank(anchorContext.getTargetSectionHint())
            || (anchorContext.getSoftSectionHints() != null && !anchorContext.getSoftSectionHints().isEmpty())) {
            scopeMode = "SOFT";
        }
        return ConversationStructureAnchor.builder()
            .rootSectionCode(safeText(anchorContext.getRootSectionCode()))
            .rootSectionTitle(safeText(anchorContext.getRootSectionTitle()))
            .targetSectionHint(safeText(anchorContext.getTargetSectionHint()))
            .structureNodeId(structureNodeId)
            .canonicalPath(canonicalPath)
            .scopeMode(scopeMode)
            .build();
    }

    private ConversationItemAnchor buildItemAnchor(RetrievalAnchorContext anchorContext) {
        if (anchorContext == null) {
            return ConversationItemAnchor.builder().build();
        }
        Integer itemIndex = anchorContext.getReferencedItemIndex();
        if (itemIndex == null && anchorContext.getStrictItemIndexes() != null && !anchorContext.getStrictItemIndexes().isEmpty()) {
            itemIndex = anchorContext.getStrictItemIndexes().get(0);
        }
        Long structureNodeId = firstStructureNodeId(anchorContext);
        String canonicalPath = firstCanonicalPath(anchorContext);
        return ConversationItemAnchor.builder()
            .itemIndex(itemIndex)
            .itemText(safeText(anchorContext.getReferencedItemText()))
            .structureNodeId(structureNodeId)
            .canonicalPath(canonicalPath)
            .build();
    }

    private String resolveSubjectSource(ConversationIntentResolution intentResolution,
                                        RetrievalAnchorContext anchorContext) {
        if (intentResolution != null && StrUtil.isNotBlank(intentResolution.getResolvedTopic())) {
            return "intent.resolvedTopic";
        }
        if (anchorContext != null && StrUtil.isNotBlank(anchorContext.getRootTopic())) {
            return "anchor.rootTopic";
        }
        return "question";
    }

    private String deriveSubjectFromIntent(ConversationIntentResolution intentResolution) {
        if (intentResolution == null) {
            return "";
        }
        String resolvedTopic = safeText(intentResolution.getResolvedTopic());
        String facet = safeText(intentResolution.getResolvedFacet());
        if (resolvedTopic.isBlank()) {
            return "";
        }
        String normalized = resolvedTopic;
        if (StrUtil.isNotBlank(facet) && normalized.endsWith(facet)) {
            normalized = normalized.substring(0, normalized.length() - facet.length()).trim();
        }
        normalized = normalized.replaceAll("(的)?(现象|可能原因|处理步骤|检查顺序|核心特性|技术规格|产品简介)$", "").trim();
        return normalized.isBlank() ? resolvedTopic : normalized;
    }

    private String deriveSubjectFromAnchor(RetrievalAnchorContext anchorContext) {
        if (anchorContext == null) {
            return "";
        }
        String rootTopic = safeText(anchorContext.getRootTopic());
        String facet = safeText(anchorContext.getTargetFacet());
        if (rootTopic.isBlank()) {
            return "";
        }
        String normalized = rootTopic;
        if (StrUtil.isNotBlank(facet) && normalized.endsWith(facet)) {
            normalized = normalized.substring(0, normalized.length() - facet.length()).trim();
        }
        return normalized.isBlank() ? rootTopic : normalized;
    }

    private String deriveTopicFromIntent(ConversationIntentResolution intentResolution) {
        if (intentResolution == null) {
            return "";
        }
        if (StrUtil.isNotBlank(intentResolution.getResolvedFacet())) {
            return intentResolution.getResolvedFacet().trim();
        }
        String resolvedTopic = safeText(intentResolution.getResolvedTopic());
        if (resolvedTopic.isBlank()) {
            return "";
        }
        return resolvedTopic;
    }

    private String buildSummary(ConversationSubjectAnchor subjectAnchor,
                                ConversationTopicAnchor topicAnchor,
                                ConversationStructureAnchor structureAnchor,
                                ConversationItemAnchor itemAnchor) {
        List<String> parts = new ArrayList<>();
        if (subjectAnchor != null && StrUtil.isNotBlank(subjectAnchor.getAnchorText())) {
            parts.add("subject=" + subjectAnchor.getAnchorText());
        }
        if (topicAnchor != null && StrUtil.isNotBlank(topicAnchor.getAnchorText())) {
            parts.add("topic=" + topicAnchor.getAnchorText());
        }
        if (structureAnchor != null) {
            String structureText = firstNonBlank(
                structureAnchor.getCanonicalPath(),
                structureAnchor.getTargetSectionHint(),
                structureAnchor.getRootSectionTitle(),
                structureAnchor.getRootSectionCode()
            );
            if (StrUtil.isNotBlank(structureText)) {
                parts.add("structure=" + structureText);
            }
            if (StrUtil.isNotBlank(structureAnchor.getScopeMode())) {
                parts.add("scope=" + structureAnchor.getScopeMode());
            }
        }
        if (itemAnchor != null && itemAnchor.getItemIndex() != null) {
            parts.add("item=" + itemAnchor.getItemIndex());
        }
        return String.join("; ", parts);
    }

    private String firstCanonicalPath(RetrievalAnchorContext anchorContext) {
        if (anchorContext == null || anchorContext.getStrictCanonicalPathHints() == null || anchorContext.getStrictCanonicalPathHints().isEmpty()) {
            return "";
        }
        return safeText(anchorContext.getStrictCanonicalPathHints().get(0));
    }

    private Long firstStructureNodeId(RetrievalAnchorContext anchorContext) {
        if (anchorContext == null || anchorContext.getStrictStructureNodeIds() == null || anchorContext.getStrictStructureNodeIds().isEmpty()) {
            return null;
        }
        return anchorContext.getStrictStructureNodeIds().get(0);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }
}
