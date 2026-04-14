package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.debug.ChatDebugTrace;
import org.javaup.ai.chatagent.rag.model.ConversationIntentRelationType;
import org.javaup.ai.chatagent.rag.model.ConversationIntentResolution;
import org.javaup.ai.chatagent.rag.model.ConversationItemAnchor;
import org.javaup.ai.chatagent.rag.model.ConversationRetrievalMode;
import org.javaup.ai.chatagent.rag.model.ConversationStructureAnchor;
import org.javaup.ai.chatagent.rag.model.ConversationSubjectAnchor;
import org.javaup.ai.chatagent.rag.model.ConversationTopicAnchor;
import org.javaup.ai.chatagent.rag.model.DocumentEvidencePolicy;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationAction;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.model.NavigationScopeMode;
import org.javaup.ai.chatagent.rag.model.RagRewriteResult;
import org.javaup.ai.chatagent.rag.model.RetrievalQuestionPlan;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.service.DocumentStructureNodeService;
import org.javaup.ai.manage.service.navigation.DocumentNavigationIndexService;
import org.javaup.enums.DocumentStructureNodeTypeEnum;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一导航内核。
 *
 * <p>它是文档问答主链中唯一负责“当前问题在问哪里”的模块。</p>
 * <p>意图识别、结构定位、章节范围、编号项范围和证据策略都必须先在这里统一收口，</p>
 * <p>后面的检索请求构造、检索执行、Prompt 组装只消费它的结果，不再各自重做导航判断。</p>
 */
@Service
public class DocumentNavigationEngine {

    private static final Pattern ROOT_SECTION_PATTERN = Pattern.compile("^(\\d+\\.\\d+)\\s+(.+)$");
    private static final Pattern FACET_SECTION_PATTERN = Pattern.compile("^(\\d+\\.\\d+\\.\\d+)\\s+(.+)$");
    private static final Pattern CHAPTER_SECTION_PATTERN = Pattern.compile("^(第[一二三四五六七八九十百\\d]+[章节条部分])\\s*(.+)$");
    private static final Pattern ORDINAL_PATTERN = Pattern.compile("第\\s*([0-9一二三四五六七八九十百]+)\\s*(条|点|项|个)");
    private static final Pattern STEP_REFERENCE_PATTERN = Pattern.compile("第\\s*([0-9一二三四五六七八九十百]+)\\s*步");

    private static final double HARD_SCOPE_SCORE_THRESHOLD = 8.0;
    private static final double HARD_SCOPE_STRICT_THRESHOLD = 12.0;

    private final DocumentStructureNodeService documentStructureNodeService;
    private final DocumentNavigationIndexService documentNavigationIndexService;
    private final ExecutionModePlanner executionModePlanner;

    public DocumentNavigationEngine(DocumentStructureNodeService documentStructureNodeService,
                                    DocumentNavigationIndexService documentNavigationIndexService,
                                    ExecutionModePlanner executionModePlanner) {
        this.documentStructureNodeService = documentStructureNodeService;
        this.documentNavigationIndexService = documentNavigationIndexService;
        this.executionModePlanner = executionModePlanner;
    }

    /**
     * 对当前轮问题做统一导航决策。
     */
    public DocumentNavigationDecision navigate(Long documentId,
                                               String question,
                                               RagRewriteResult rewriteResult,
                                               ConversationIntentResolution intentResolution,
                                               List<ConversationExchangeView> recentCompletedExchanges) {
        String normalizedQuestion = safeText(question);
        PreviousNavigationSnapshot previousSnapshot = loadPreviousSnapshot(recentCompletedExchanges, documentId);
        Map<Long, SuperAgentDocumentStructureNode> nodeMap = documentId == null
            ? Map.of()
            : documentStructureNodeService.nodeMap(documentId, null);
        List<SuperAgentDocumentStructureNode> sectionNodes = nodeMap.values().stream()
            .filter(node -> node != null && DocumentStructureNodeTypeEnum.SECTION.getCode().equals(node.getNodeType()))
            .sorted(Comparator.comparing(SuperAgentDocumentStructureNode::getNodeNo, Comparator.nullsLast(Integer::compareTo)))
            .toList();

        ConversationIntentRelationType relationType = intentResolution == null || intentResolution.getRelationType() == null
            ? ConversationIntentRelationType.UNKNOWN
            : intentResolution.getRelationType();
        List<Integer> explicitItemIndexes = resolveExplicitItemIndexes(normalizedQuestion);
        boolean sectionAdjacency = asksSectionAdjacency(normalizedQuestion);

        ConversationSubjectAnchor subjectAnchor = buildSubjectAnchor(normalizedQuestion, intentResolution, previousSnapshot);
        ConversationTopicAnchor topicAnchor = buildTopicAnchor(intentResolution, previousSnapshot);
        DocumentNavigationAction action = determineAction(relationType, topicAnchor, previousSnapshot, explicitItemIndexes, sectionAdjacency);

        SectionNodeMatch previousSection = resolvePreviousSection(documentId, previousSnapshot, sectionNodes);
        SectionNodeMatch targetSection = resolveTargetSection(
            documentId,
            sectionNodes,
            previousSection,
            previousSnapshot,
            subjectAnchor,
            topicAnchor,
            intentResolution,
            rewriteResult,
            normalizedQuestion,
            action
        );
        ItemResolution itemResolution = resolveItemResolution(nodeMap, targetSection, explicitItemIndexes);

        ScopeResolution scopeResolution = buildScopeResolution(
            intentResolution,
            rewriteResult,
            normalizedQuestion,
            previousSnapshot,
            subjectAnchor,
            topicAnchor,
            action,
            targetSection,
            itemResolution
        );

        RetrievalQuestionPlan retrievalPlan = buildRetrievalPlan(
            normalizedQuestion,
            rewriteResult,
            intentResolution,
            subjectAnchor,
            topicAnchor
        );
        ConversationStructureAnchor structureAnchor = buildStructureAnchor(targetSection, itemResolution, scopeResolution);
        ConversationItemAnchor itemAnchor = buildItemAnchor(itemResolution);
        String summaryText = buildNavigationSummary(subjectAnchor, topicAnchor, structureAnchor, itemAnchor);

        ExecutionMode executionMode = executionModePlanner.plan(action, intentResolution, normalizedQuestion, targetSection != null);

        DocumentEvidencePolicy evidencePolicy = DocumentEvidencePolicy.builder()
            .targetStructureRequired(scopeResolution.scopeMode() != NavigationScopeMode.NONE)
            .exactItemRequired(itemResolution.exactItemRequired())
            .siblingContextRequired(action == DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP)
            .noEvidenceWhenTargetMissing(scopeResolution.missingRequestedStructure())
            .build();

        List<String> notes = new ArrayList<>();
        if (scopeResolution.missingRequestedStructure()) {
            notes.add("当前问题指向了一个文档结构树中不存在的目标章节。");
        }

        return DocumentNavigationDecision.builder()
            .relationType(relationType)
            .navigationAction(action)
            .retrievalMode(intentResolution == null ? ConversationRetrievalMode.UNKNOWN : intentResolution.getRetrievalMode())
            .executionMode(executionMode)
            .subjectAnchor(subjectAnchor)
            .topicAnchor(topicAnchor)
            .structureAnchor(structureAnchor)
            .itemAnchor(itemAnchor)
            .retrievalPlan(retrievalPlan)
            .evidencePolicy(evidencePolicy)
            .anchorApplied(true)
            .anchorExchangeId(previousSnapshot.exchangeId())
            .anchorSourceQuestion(previousSnapshot.retrievalQuestion())
            .missingRequestedStructure(scopeResolution.missingRequestedStructure())
            .summaryText(summaryText)
            .queryContextHints(scopeResolution.queryContextHints())
            .softSectionHints(scopeResolution.softSectionHints())
            .strictSectionHints(scopeResolution.strictSectionHints())
            .strictCanonicalPathHints(scopeResolution.strictCanonicalPathHints())
            .strictStructureNodeIds(scopeResolution.strictStructureNodeIds())
            .strictItemIndexes(scopeResolution.strictItemIndexes())
            .notes(notes)
            .build();
    }

    /**
     * 输出给 LLM 和日志使用的上一轮导航摘要。
     */
    public String describePreviousNavigation(List<ConversationExchangeView> recentCompletedExchanges, Long documentId) {
        PreviousNavigationSnapshot snapshot = loadPreviousSnapshot(recentCompletedExchanges, documentId);
        if (snapshot.isEmpty()) {
            return "无";
        }
        return "subject=" + safeText(snapshot.subjectText())
            + "; topic=" + safeText(snapshot.topicText())
            + "; rootSectionCode=" + safeText(snapshot.rootSectionCode())
            + "; rootSectionTitle=" + safeText(snapshot.rootSectionTitle())
            + "; targetSectionHint=" + safeText(snapshot.targetSectionHint())
            + "; canonicalPath=" + safeText(snapshot.canonicalPath())
            + "; itemIndex=" + (snapshot.itemIndex() == null ? "" : snapshot.itemIndex())
            + "; retrievalQuestion=" + safeText(snapshot.retrievalQuestion());
    }

    private PreviousNavigationSnapshot loadPreviousSnapshot(List<ConversationExchangeView> recentCompletedExchanges,
                                                            Long documentId) {
        if (recentCompletedExchanges == null || recentCompletedExchanges.isEmpty()) {
            return PreviousNavigationSnapshot.empty();
        }
        for (int index = recentCompletedExchanges.size() - 1; index >= 0; index--) {
            ConversationExchangeView exchange = recentCompletedExchanges.get(index);
            if (exchange == null || exchange.getDebugTrace() == null) {
                continue;
            }
            ChatDebugTrace debugTrace = exchange.getDebugTrace();
            if (documentId != null
                && debugTrace.getSelectedDocumentId() != null
                && !Objects.equals(documentId, debugTrace.getSelectedDocumentId())) {
                continue;
            }
            if (debugTrace.getNavigationDecision() != null) {
                return PreviousNavigationSnapshot.fromNavigationDecision(exchange.getExchangeId(), debugTrace.getNavigationDecision());
            }
        }
        return PreviousNavigationSnapshot.empty();
    }

    private ConversationSubjectAnchor buildSubjectAnchor(String question,
                                                         ConversationIntentResolution intentResolution,
                                                         PreviousNavigationSnapshot previousSnapshot) {
        String resolvedTopic = intentResolution == null ? "" : safeText(intentResolution.getResolvedTopic());
        String facet = intentResolution == null ? "" : safeText(intentResolution.getResolvedFacet());
        String subjectText = firstNonBlank(
            stripTrailingFacet(resolvedTopic, facet),
            previousSnapshot.subjectText(),
            safeText(question)
        );
        return ConversationSubjectAnchor.builder()
            .anchorText(subjectText)
            .source(StrUtil.isNotBlank(resolvedTopic) ? "intent.resolvedTopic" : "previous.navigation")
            .inherited(intentResolution != null && intentResolution.getRelationType() == ConversationIntentRelationType.FOLLOW_UP)
            .build();
    }

    private ConversationTopicAnchor buildTopicAnchor(ConversationIntentResolution intentResolution,
                                                     PreviousNavigationSnapshot previousSnapshot) {
        String facet = intentResolution == null ? "" : safeText(intentResolution.getResolvedFacet());
        String topicText = firstNonBlank(
            facet,
            intentResolution == null ? "" : safeText(intentResolution.getInformationNeed()),
            previousSnapshot.topicText()
        );
        return ConversationTopicAnchor.builder()
            .anchorText(topicText)
            .facet(facet)
            .informationNeed(intentResolution == null ? "" : safeText(intentResolution.getInformationNeed()))
            .build();
    }

    private DocumentNavigationAction determineAction(ConversationIntentRelationType relationType,
                                                     ConversationTopicAnchor topicAnchor,
                                                     PreviousNavigationSnapshot previousSnapshot,
                                                     List<Integer> explicitItemIndexes,
                                                     boolean sectionAdjacency) {
        if (sectionAdjacency) {
            return DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP;
        }
        if (explicitItemIndexes != null && !explicitItemIndexes.isEmpty()) {
            return DocumentNavigationAction.ITEM_REFERENCE;
        }
        if (relationType == ConversationIntentRelationType.TOPIC_SWITCH) {
            return DocumentNavigationAction.TOPIC_SWITCH;
        }
        if (relationType == ConversationIntentRelationType.FRESH_TOPIC) {
            return DocumentNavigationAction.FRESH_TOPIC;
        }
        if (relationType == ConversationIntentRelationType.FOLLOW_UP) {
            String previousTopic = safeText(previousSnapshot.topicText());
            String currentTopic = topicAnchor == null ? "" : safeText(topicAnchor.getFacet());
            if (StrUtil.isNotBlank(previousTopic) && StrUtil.isNotBlank(currentTopic) && !previousTopic.equals(currentTopic)) {
                return DocumentNavigationAction.SIBLING_SECTION_SWITCH;
            }
            return DocumentNavigationAction.TOPIC_CONTINUE;
        }
        return DocumentNavigationAction.TOPIC_CONTINUE;
    }

    private SectionNodeMatch resolvePreviousSection(Long documentId,
                                                    PreviousNavigationSnapshot previousSnapshot,
                                                    List<SuperAgentDocumentStructureNode> sectionNodes) {
        if (previousSnapshot == null || previousSnapshot.isEmpty() || sectionNodes.isEmpty()) {
            return null;
        }
        if (previousSnapshot.structureNodeId() != null) {
            for (SuperAgentDocumentStructureNode sectionNode : sectionNodes) {
                if (Objects.equals(previousSnapshot.structureNodeId(), sectionNode.getId())) {
                    return toSectionNodeMatch(sectionNode, sectionNodes, 100.0);
                }
            }
        }
        String canonicalPath = safeText(previousSnapshot.canonicalPath());
        if (StrUtil.isNotBlank(canonicalPath)) {
            for (SuperAgentDocumentStructureNode sectionNode : sectionNodes) {
                if (canonicalPath.equals(safeText(sectionNode.getCanonicalPath()))) {
                    return toSectionNodeMatch(sectionNode, sectionNodes, 100.0);
                }
            }
        }
        String sectionCode = safeText(previousSnapshot.rootSectionCode());
        if (StrUtil.isNotBlank(sectionCode)) {
            for (SuperAgentDocumentStructureNode sectionNode : sectionNodes) {
                if (sectionCode.equals(safeText(sectionNode.getNodeCode()))) {
                    return toSectionNodeMatch(sectionNode, sectionNodes, 100.0);
                }
            }
        }
        return findBestSectionMatch(
            documentId,
            sectionNodes,
            List.of(previousSnapshot.rootSectionTitle(), previousSnapshot.targetSectionHint()),
            List.of(previousSnapshot.subjectText(), previousSnapshot.topicText()),
            previousSnapshot.subjectText()
        );
    }

    private SectionNodeMatch resolveTargetSection(Long documentId,
                                                  List<SuperAgentDocumentStructureNode> sectionNodes,
                                                  SectionNodeMatch previousSection,
                                                  PreviousNavigationSnapshot previousSnapshot,
                                                  ConversationSubjectAnchor subjectAnchor,
                                                  ConversationTopicAnchor topicAnchor,
                                                  ConversationIntentResolution intentResolution,
                                                  RagRewriteResult rewriteResult,
                                                  String question,
                                                  DocumentNavigationAction action) {
        if (sectionNodes.isEmpty()) {
            return null;
        }
        String subjectKeyword = subjectAnchor == null ? "" : safeText(subjectAnchor.getAnchorText());
        List<String> preferredHints = mergeNonBlankHints(
            intentResolution == null ? List.of() : intentResolution.getSoftSectionHints(),
            List.of(
                intentResolution == null ? "" : safeText(intentResolution.getResolvedTopic()),
                intentResolution == null ? "" : safeText(intentResolution.getResolvedFacet()),
                rewriteResult == null ? "" : safeText(rewriteResult.getRewrittenQuestion()),
                question
            )
        );
        List<String> semanticTerms = List.of(
            subjectKeyword,
            topicAnchor == null ? "" : safeText(topicAnchor.getFacet()),
            topicAnchor == null ? "" : safeText(topicAnchor.getInformationNeed())
        );

        return switch (action) {
            case ITEM_REFERENCE, TOPIC_CONTINUE -> previousSection != null
                ? previousSection
                : findBestSectionMatch(documentId, sectionNodes, preferredHints, semanticTerms, subjectKeyword);
            case SECTION_ADJACENCY_LOOKUP -> previousSection != null
                ? previousSection
                : findBestSectionMatch(documentId, sectionNodes, preferredHints, semanticTerms, subjectKeyword);
            case SIBLING_SECTION_SWITCH -> {
                SectionNodeMatch siblingMatch = findSiblingMatch(documentId, sectionNodes, previousSection, preferredHints, semanticTerms, subjectKeyword);
                yield siblingMatch != null ? siblingMatch : findBestSectionMatch(documentId, sectionNodes, preferredHints, semanticTerms, subjectKeyword);
            }
            case TOPIC_SWITCH, FRESH_TOPIC -> findBestSectionMatch(documentId, sectionNodes, preferredHints, semanticTerms, subjectKeyword);
            case CHILD_SECTION_DESCEND, ANCESTOR_SECTION_RETURN -> findBestSectionMatch(documentId, sectionNodes, preferredHints, semanticTerms, subjectKeyword);
        };
    }

    private ItemResolution resolveItemResolution(Map<Long, SuperAgentDocumentStructureNode> nodeMap,
                                                 SectionNodeMatch targetSection,
                                                 List<Integer> explicitItemIndexes) {
        if (targetSection == null || explicitItemIndexes == null || explicitItemIndexes.isEmpty() || nodeMap.isEmpty()) {
            return ItemResolution.empty();
        }
        List<SuperAgentDocumentStructureNode> candidateItems = nodeMap.values().stream()
            .filter(node -> node != null
                && Objects.equals(node.getParentNodeId(), targetSection.nodeId())
                && (DocumentStructureNodeTypeEnum.STEP.getCode().equals(node.getNodeType())
                || DocumentStructureNodeTypeEnum.LIST_ITEM.getCode().equals(node.getNodeType())))
            .sorted(Comparator.comparing(SuperAgentDocumentStructureNode::getNodeNo, Comparator.nullsLast(Integer::compareTo)))
            .toList();
        if (candidateItems.isEmpty()) {
            return ItemResolution.empty();
        }
        LinkedHashSet<Integer> matchedIndexes = new LinkedHashSet<>();
        SuperAgentDocumentStructureNode exactSingleItem = null;
        if (explicitItemIndexes.size() == 1) {
            Integer targetIndex = explicitItemIndexes.get(0);
            for (SuperAgentDocumentStructureNode candidateItem : candidateItems) {
                if (Objects.equals(targetIndex, candidateItem.getItemIndex())) {
                    exactSingleItem = candidateItem;
                    matchedIndexes.add(targetIndex);
                    break;
                }
            }
        }
        else {
            for (Integer explicitItemIndex : explicitItemIndexes) {
                for (SuperAgentDocumentStructureNode candidateItem : candidateItems) {
                    if (Objects.equals(explicitItemIndex, candidateItem.getItemIndex())) {
                        matchedIndexes.add(explicitItemIndex);
                        break;
                    }
                }
            }
        }
        if (matchedIndexes.isEmpty()) {
            return ItemResolution.empty();
        }
        if (exactSingleItem != null) {
            return ItemResolution.exactItem(exactSingleItem);
        }
        return ItemResolution.itemIndexes(new ArrayList<>(matchedIndexes));
    }

    private ScopeResolution buildScopeResolution(ConversationIntentResolution intentResolution,
                                                 RagRewriteResult rewriteResult,
                                                 String question,
                                                 PreviousNavigationSnapshot previousSnapshot,
                                                 ConversationSubjectAnchor subjectAnchor,
                                                 ConversationTopicAnchor topicAnchor,
                                                 DocumentNavigationAction action,
                                                 SectionNodeMatch targetSection,
                                                 ItemResolution itemResolution) {
        List<String> intentHints = intentResolution == null ? List.of() : intentResolution.getSoftSectionHints();
        boolean missingRequestedStructure = targetSection == null && containsConcreteSectionHint(intentHints);
        if (missingRequestedStructure) {
            return ScopeResolution.builder()
                .scopeMode(NavigationScopeMode.NONE)
                .missingRequestedStructure(true)
                .targetSectionHint(firstNonBlank(intentHints.isEmpty() ? "" : intentHints.get(0), safeText(question)))
                .softSectionHints(new ArrayList<>(intentHints))
                .queryContextHints(buildQueryContextHints(subjectAnchor, topicAnchor, rewriteResult, question))
                .build();
        }

        List<String> softSectionHints = buildSoftSectionHints(intentHints, targetSection, previousSnapshot);
        List<String> queryContextHints = buildQueryContextHints(subjectAnchor, topicAnchor, rewriteResult, question);

        if (action == DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP) {
            String parentSectionPath = targetSection == null ? "" : safeText(targetSection.parentSectionPath());
            return ScopeResolution.builder()
                .scopeMode(NavigationScopeMode.HARD_PARENT_WITH_SIBLINGS)
                .targetSectionHint(firstNonBlank(parentSectionPath, targetSection == null ? "" : targetSection.sectionPath()))
                .strictSectionHints(parentSectionPath.isBlank() ? List.of() : List.of(parentSectionPath))
                .softSectionHints(softSectionHints)
                .queryContextHints(mergeNonBlankHints(queryContextHints, List.of("章节", "上一节", "下一节")))
                .build();
        }

        if (itemResolution.exactItemRequired()) {
            return ScopeResolution.builder()
                .scopeMode(NavigationScopeMode.HARD_ITEM)
                .targetSectionHint(targetSection == null ? "" : targetSection.sectionPath())
                .strictSectionHints(targetSection == null ? List.of() : List.of(targetSection.sectionPath()))
                .strictCanonicalPathHints(List.of(itemResolution.canonicalPath()))
                .strictStructureNodeIds(List.of(itemResolution.structureNodeId()))
                .strictItemIndexes(List.of(itemResolution.referencedItemIndex()))
                .softSectionHints(softSectionHints)
                .queryContextHints(queryContextHints)
                .build();
        }

        if (targetSection != null) {
            boolean highConfidence = targetSection.matchScore() >= HARD_SCOPE_SCORE_THRESHOLD;
            boolean strictConfidence = targetSection.matchScore() >= HARD_SCOPE_STRICT_THRESHOLD;
            // TOPIC_SWITCH / FRESH_TOPIC 要求更高置信度才允许 HARD
            boolean isNewTopic = action == DocumentNavigationAction.TOPIC_SWITCH
                || action == DocumentNavigationAction.FRESH_TOPIC;
            boolean useHard = isNewTopic ? strictConfidence : highConfidence;
            if (useHard) {
                return ScopeResolution.builder()
                    .scopeMode(NavigationScopeMode.HARD_SECTION)
                    .targetSectionHint(targetSection.sectionPath())
                    .strictSectionHints(List.of(targetSection.sectionPath()))
                    .strictItemIndexes(itemResolution.itemIndexes())
                    .softSectionHints(softSectionHints)
                    .queryContextHints(queryContextHints)
                    .build();
            }
            // 低置信度降级为 SOFT
            return ScopeResolution.builder()
                .scopeMode(NavigationScopeMode.SOFT)
                .targetSectionHint(targetSection.sectionPath())
                .softSectionHints(mergeNonBlankHints(List.of(targetSection.sectionPath()), softSectionHints))
                .queryContextHints(queryContextHints)
                .build();
        }

        return ScopeResolution.builder()
            .scopeMode(NavigationScopeMode.SOFT)
            .targetSectionHint(firstNonBlank(intentHints.isEmpty() ? "" : intentHints.get(0), previousSnapshot.targetSectionHint()))
            .softSectionHints(softSectionHints)
            .queryContextHints(queryContextHints)
            .build();
    }

    private RetrievalQuestionPlan buildRetrievalPlan(String question,
                                                    RagRewriteResult rewriteResult,
                                                    ConversationIntentResolution intentResolution,
                                                    ConversationSubjectAnchor subjectAnchor,
                                                    ConversationTopicAnchor topicAnchor) {
        if (intentResolution != null
            && intentResolution.getRetrievalMode() == ConversationRetrievalMode.ANALYTIC_DECOMPOSITION
            && intentResolution.getRetrievalSubQuestions() != null
            && !intentResolution.getRetrievalSubQuestions().isEmpty()) {
            List<String> subQuestions = intentResolution.getRetrievalSubQuestions().stream()
                .map(item -> contextualizeSubQuestion(item, subjectAnchor, topicAnchor))
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
            String retrievalQuestion = firstNonBlank(
                rewriteResult == null ? "" : safeText(rewriteResult.getRewrittenQuestion()),
                intentResolution.getRetrievalQuery(),
                question
            );
            return new RetrievalQuestionPlan(retrievalQuestion, subQuestions);
        }
        String retrievalQuestion = firstNonBlank(
            rewriteResult == null ? "" : safeText(rewriteResult.getRewrittenQuestion()),
            intentResolution == null ? "" : safeText(intentResolution.getRetrievalQuery()),
            question
        );
        return new RetrievalQuestionPlan(retrievalQuestion, List.of(retrievalQuestion));
    }

    private ConversationStructureAnchor buildStructureAnchor(SectionNodeMatch targetSection,
                                                             ItemResolution itemResolution,
                                                             ScopeResolution scopeResolution) {
        return ConversationStructureAnchor.builder()
            .rootSectionCode(targetSection == null ? "" : targetSection.nodeCode())
            .rootSectionTitle(targetSection == null ? "" : targetSection.displayTitle())
            .targetSectionHint(scopeResolution.targetSectionHint())
            .structureNodeId(itemResolution.exactItemRequired() ? itemResolution.structureNodeId() : (targetSection == null ? null : targetSection.nodeId()))
            .canonicalPath(itemResolution.exactItemRequired() ? itemResolution.canonicalPath() : "")
            .scopeMode(scopeResolution.scopeMode().name())
            .build();
    }

    private ConversationItemAnchor buildItemAnchor(ItemResolution itemResolution) {
        return ConversationItemAnchor.builder()
            .itemIndex(itemResolution.referencedItemIndex())
            .itemText(itemResolution.referencedItemText())
            .structureNodeId(itemResolution.exactItemRequired() ? itemResolution.structureNodeId() : null)
            .canonicalPath(itemResolution.exactItemRequired() ? itemResolution.canonicalPath() : "")
            .build();
    }

    private List<String> buildSoftSectionHints(List<String> intentHints,
                                               SectionNodeMatch targetSection,
                                               PreviousNavigationSnapshot previousSnapshot) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (targetSection != null) {
            hints.add(targetSection.sectionPath());
            hints.add(targetSection.displayTitle());
            if (StrUtil.isNotBlank(targetSection.parentSectionPath())) {
                hints.add(targetSection.parentSectionPath());
            }
        }
        if (intentHints != null) {
            intentHints.stream().filter(StrUtil::isNotBlank).map(String::trim).forEach(hints::add);
        }
        if (StrUtil.isNotBlank(previousSnapshot.targetSectionHint())) {
            hints.add(previousSnapshot.targetSectionHint());
        }
        return new ArrayList<>(hints);
    }

    private List<String> buildQueryContextHints(ConversationSubjectAnchor subjectAnchor,
                                                ConversationTopicAnchor topicAnchor,
                                                RagRewriteResult rewriteResult,
                                                String question) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (subjectAnchor != null && StrUtil.isNotBlank(subjectAnchor.getAnchorText())) {
            hints.add(subjectAnchor.getAnchorText());
        }
        if (topicAnchor != null && StrUtil.isNotBlank(topicAnchor.getFacet())) {
            hints.add(topicAnchor.getFacet());
        }
        if (rewriteResult != null && StrUtil.isNotBlank(rewriteResult.getRewrittenQuestion())) {
            String rewritten = rewriteResult.getRewrittenQuestion().trim();
            for (String segment : rewritten.split("[\\s、，,；;：:（）()\\-的与和中里]+")) {
                String trimmed = segment.trim();
                if (trimmed.length() >= 2) {
                    hints.add(trimmed);
                    if (hints.size() >= 8) {
                        break;
                    }
                }
            }
        }
        if (StrUtil.isNotBlank(question)) {
            resolveExplicitItemIndexes(question).stream()
                .map(index -> "第" + index + "步")
                .forEach(hints::add);
        }
        return new ArrayList<>(hints);
    }

    private SectionNodeMatch findSiblingMatch(Long documentId,
                                              List<SuperAgentDocumentStructureNode> sectionNodes,
                                              SectionNodeMatch previousSection,
                                              List<String> preferredHints,
                                              List<String> semanticTerms,
                                              String subjectKeyword) {
        if (previousSection == null || StrUtil.isBlank(previousSection.parentSectionPath())) {
            return null;
        }
        List<SuperAgentDocumentStructureNode> siblingCandidates = sectionNodes.stream()
            .filter(node -> safeText(node.getSectionPath()).startsWith(previousSection.parentSectionPath()))
            .toList();
        return findBestSectionMatch(documentId, siblingCandidates, preferredHints, semanticTerms, subjectKeyword);
    }

    private SectionNodeMatch findBestSectionMatch(Long documentId,
                                                  List<SuperAgentDocumentStructureNode> sectionNodes,
                                                  List<String> preferredHints,
                                                  List<String> semanticTerms,
                                                  String subjectKeyword) {
        if (sectionNodes == null || sectionNodes.isEmpty()) {
            return null;
        }
        // 1. 精确匹配优先（nodeCode、完整标题、sectionPath）
        SectionNodeMatch exactMatch = findExactSectionMatch(sectionNodes, preferredHints, subjectKeyword);
        if (exactMatch != null) {
            return exactMatch;
        }
        // 2. ES IK 分词语义匹配（title/sectionPath 高权重，contentText 低权重）
        SectionNodeMatch esMatch = findEsSemanticSectionMatch(documentId, sectionNodes, semanticTerms);
        if (esMatch != null) {
            return esMatch;
        }
        return null;
    }

    /**
     * 基于 ES 导航索引的语义匹配。
     * IK 分词器自动处理中文分词，title/sectionPath 命中权重远高于 contentText。
     */
    private SectionNodeMatch findEsSemanticSectionMatch(Long documentId,
                                                         List<SuperAgentDocumentStructureNode> sectionNodes,
                                                         List<String> semanticTerms) {
        if (documentId == null || semanticTerms == null || semanticTerms.isEmpty()) {
            return null;
        }
        String topic = semanticTerms.stream().filter(StrUtil::isNotBlank).findFirst().orElse("");
        String facet = semanticTerms.size() > 1 ? semanticTerms.get(1) : "";
        if (StrUtil.isBlank(topic) && StrUtil.isBlank(facet)) {
            return null;
        }
        List<DocumentNavigationIndexService.NavigationMatchResult> esResults =
            documentNavigationIndexService.searchSections(documentId, topic, facet, 3);
        if (esResults.isEmpty()) {
            return null;
        }
        DocumentNavigationIndexService.NavigationMatchResult bestResult = esResults.get(0);
        if (bestResult.score() < 1.0) {
            return null;
        }
        for (SuperAgentDocumentStructureNode sectionNode : sectionNodes) {
            if (sectionNode != null && Objects.equals(bestResult.nodeId(), sectionNode.getId())) {
                return toSectionNodeMatch(sectionNode, sectionNodes, bestResult.score());
            }
        }
        return null;
    }

    private SectionNodeMatch findExactSectionMatch(List<SuperAgentDocumentStructureNode> sectionNodes,
                                                   List<String> preferredHints,
                                                   String subjectKeyword) {
        if (preferredHints == null || preferredHints.isEmpty()) {
            return null;
        }
        String normalizedSubject = normalizeComparableText(subjectKeyword);
        for (String preferredHint : preferredHints) {
            String normalizedHint = safeText(preferredHint);
            if (normalizedHint.isBlank()) {
                continue;
            }
            String normalizedKey = normalizeComparableText(normalizedHint);
            String sectionCode = extractSectionCode(normalizedHint);
            // 显式编号/code 命中 — 最高优先级
            if (StrUtil.isNotBlank(sectionCode)) {
                for (SuperAgentDocumentStructureNode sectionNode : sectionNodes) {
                    if (sectionNode != null && sectionCode.equals(safeText(sectionNode.getNodeCode()))) {
                        return toSectionNodeMatch(sectionNode, sectionNodes, 100.0);
                    }
                }
            }
            for (SuperAgentDocumentStructureNode sectionNode : sectionNodes) {
                if (sectionNode == null) {
                    continue;
                }
                String titleKey = normalizeComparableText(sectionNode.getTitle());
                String sectionPathKey = normalizeComparableText(sectionNode.getSectionPath());
                // 精确标题/路径完全相等
                if (normalizedKey.equals(titleKey) || normalizedKey.equals(sectionPathKey)) {
                    return toSectionNodeMatch(sectionNode, sectionNodes, 80.0);
                }
                // contains 匹配 — 短 hint（< 4 字）必须校验父节点链包含当前 subject
                if (titleKey.contains(normalizedKey) || sectionPathKey.contains(normalizedKey)) {
                    if (normalizedKey.length() < 4 && StrUtil.isNotBlank(normalizedSubject)) {
                        if (!parentChainContainsSubject(sectionNode, sectionNodes, normalizedSubject)) {
                            continue;
                        }
                    }
                    return toSectionNodeMatch(sectionNode, sectionNodes, 50.0);
                }
            }
        }
        return null;
    }

    /**
     * 校验节点的父节点链是否包含当前 subject 关键词。
     * 用于防止短 facet 词（如"检查顺序"）匹配到错误主题的同名章节。
     */
    private boolean parentChainContainsSubject(SuperAgentDocumentStructureNode node,
                                                List<SuperAgentDocumentStructureNode> sectionNodes,
                                                String normalizedSubject) {
        if (StrUtil.isBlank(normalizedSubject)) {
            return true;
        }
        SuperAgentDocumentStructureNode current = node;
        int depth = 0;
        while (current != null && depth < 10) {
            String titleKey = normalizeComparableText(current.getTitle());
            String pathKey = normalizeComparableText(current.getSectionPath());
            if (titleKey.contains(normalizedSubject) || pathKey.contains(normalizedSubject)) {
                return true;
            }
            if (current.getParentNodeId() == null) {
                break;
            }
            current = findNodeById(sectionNodes, current.getParentNodeId());
            depth++;
        }
        return false;
    }

    private SuperAgentDocumentStructureNode findNodeById(List<SuperAgentDocumentStructureNode> sectionNodes, Long nodeId) {
        if (nodeId == null) {
            return null;
        }
        for (SuperAgentDocumentStructureNode node : sectionNodes) {
            if (node != null && Objects.equals(nodeId, node.getId())) {
                return node;
            }
        }
        return null;
    }

    private SectionNodeMatch toSectionNodeMatch(SuperAgentDocumentStructureNode sectionNode,
                                                List<SuperAgentDocumentStructureNode> sectionNodes,
                                                double matchScore) {
        String parentSectionPath = "";
        if (sectionNode != null && sectionNode.getParentNodeId() != null) {
            for (SuperAgentDocumentStructureNode candidate : sectionNodes) {
                if (candidate != null && Objects.equals(candidate.getId(), sectionNode.getParentNodeId())) {
                    parentSectionPath = safeText(candidate.getSectionPath());
                    break;
                }
            }
        }
        return new SectionNodeMatch(
            sectionNode == null ? null : sectionNode.getId(),
            sectionNode == null ? "" : safeText(sectionNode.getNodeCode()),
            sectionNode == null ? "" : firstNonBlank(safeText(sectionNode.getSectionPath()), safeText(sectionNode.getTitle())),
            sectionNode == null ? "" : safeText(sectionNode.getSectionPath()),
            parentSectionPath,
            matchScore
        );
    }

    private boolean containsConcreteSectionHint(List<String> sectionHints) {
        if (sectionHints == null || sectionHints.isEmpty()) {
            return false;
        }
        return sectionHints.stream()
            .filter(StrUtil::isNotBlank)
            .map(String::trim)
            .anyMatch(this::looksLikeConcreteSectionHint);
    }

    private boolean looksLikeConcreteSectionHint(String sectionHint) {
        String normalized = safeText(sectionHint);
        if (normalized.isBlank()) {
            return false;
        }
        return FACET_SECTION_PATTERN.matcher(normalized).find()
            || ROOT_SECTION_PATTERN.matcher(normalized).find()
            || CHAPTER_SECTION_PATTERN.matcher(normalized).find()
            || normalized.contains(">");
    }

    private List<Integer> resolveExplicitItemIndexes(String question) {
        String normalized = safeText(question);
        if (normalized.isBlank()) {
            return List.of();
        }
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        Matcher stepMatcher = STEP_REFERENCE_PATTERN.matcher(normalized);
        while (stepMatcher.find()) {
            Integer parsed = parseChineseNumber(stepMatcher.group(1));
            if (parsed != null) {
                indexes.add(parsed);
            }
        }
        Matcher ordinalMatcher = ORDINAL_PATTERN.matcher(normalized);
        while (ordinalMatcher.find()) {
            Integer parsed = parseChineseNumber(ordinalMatcher.group(1));
            if (parsed != null) {
                indexes.add(parsed);
            }
        }
        return new ArrayList<>(indexes);
    }

    private boolean asksSectionAdjacency(String question) {
        String normalized = safeText(question);
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.contains("上一节")
            || normalized.contains("下一节")
            || normalized.contains("前一节")
            || normalized.contains("后一节")
            || normalized.contains("上一个章节")
            || normalized.contains("下一个章节")
            || normalized.contains("章节位置")
            || normalized.contains("属于哪个章节");
    }

    private String contextualizeSubQuestion(String subQuestion,
                                            ConversationSubjectAnchor subjectAnchor,
                                            ConversationTopicAnchor topicAnchor) {
        String normalized = safeText(subQuestion);
        if (normalized.isBlank()) {
            return "";
        }
        if (subjectAnchor != null && StrUtil.isNotBlank(subjectAnchor.getAnchorText()) && !normalized.contains(subjectAnchor.getAnchorText())) {
            return (subjectAnchor.getAnchorText() + " " + normalized).trim();
        }
        if (topicAnchor != null && StrUtil.isNotBlank(topicAnchor.getFacet()) && !normalized.contains(topicAnchor.getFacet())) {
            return (normalized + " " + topicAnchor.getFacet()).trim();
        }
        return normalized;
    }

    private List<String> mergeNonBlankHints(List<String> primary, List<String> secondary) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (primary != null) {
            primary.stream().filter(StrUtil::isNotBlank).map(String::trim).forEach(merged::add);
        }
        if (secondary != null) {
            secondary.stream().filter(StrUtil::isNotBlank).map(String::trim).forEach(merged::add);
        }
        return new ArrayList<>(merged);
    }

    private String extractSectionCode(String text) {
        String normalized = safeText(text);
        Matcher facetMatcher = FACET_SECTION_PATTERN.matcher(normalized);
        if (facetMatcher.find()) {
            return safeText(facetMatcher.group(1));
        }
        Matcher rootMatcher = ROOT_SECTION_PATTERN.matcher(normalized);
        if (rootMatcher.find()) {
            return safeText(rootMatcher.group(1));
        }
        return "";
    }

    private String normalizeComparableText(String text) {
        return safeText(text)
            .replaceAll("[\\s>`*#_\\-]+", "")
            .toLowerCase();
    }

    private String stripTrailingFacet(String resolvedTopic, String facet) {
        String normalized = safeText(resolvedTopic);
        String normalizedFacet = safeText(facet);
        if (normalized.isBlank() || normalizedFacet.isBlank()) {
            return normalized;
        }
        if (normalized.endsWith(normalizedFacet)) {
            return normalized.substring(0, normalized.length() - normalizedFacet.length()).trim();
        }
        return normalized;
    }

    private String buildNavigationSummary(ConversationSubjectAnchor subjectAnchor,
                                          ConversationTopicAnchor topicAnchor,
                                          ConversationStructureAnchor structureAnchor,
                                          ConversationItemAnchor itemAnchor) {
        List<String> parts = new ArrayList<>();
        if (subjectAnchor != null && StrUtil.isNotBlank(subjectAnchor.getAnchorText())) {
            parts.add("subject=" + subjectAnchor.getAnchorText());
        }
        if (topicAnchor != null && StrUtil.isNotBlank(topicAnchor.getFacet())) {
            parts.add("topic=" + topicAnchor.getFacet());
        }
        if (structureAnchor != null) {
            String structureText = firstNonBlank(
                structureAnchor.getCanonicalPath(),
                structureAnchor.getTargetSectionHint(),
                structureAnchor.getRootSectionTitle()
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private Integer parseChineseNumber(String text) {
        String normalized = safeText(text);
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(normalized);
        }
        Map<Character, Integer> digitMap = Map.of(
            '一', 1, '二', 2, '三', 3, '四', 4, '五', 5,
            '六', 6, '七', 7, '八', 8, '九', 9
        );
        if ("十".equals(normalized)) {
            return 10;
        }
        if (normalized.startsWith("十") && normalized.length() == 2) {
            return 10 + digitMap.getOrDefault(normalized.charAt(1), 0);
        }
        if (normalized.endsWith("十") && normalized.length() == 2) {
            return digitMap.getOrDefault(normalized.charAt(0), 0) * 10;
        }
        if (normalized.contains("十") && normalized.length() == 3) {
            return digitMap.getOrDefault(normalized.charAt(0), 0) * 10 + digitMap.getOrDefault(normalized.charAt(2), 0);
        }
        return digitMap.getOrDefault(normalized.charAt(0), null);
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    private record SectionNodeMatch(
        Long nodeId,
        String nodeCode,
        String displayTitle,
        String sectionPath,
        String parentSectionPath,
        double matchScore
    ) {
    }

    private record PreviousNavigationSnapshot(
        Long exchangeId,
        String subjectText,
        String topicText,
        String rootSectionCode,
        String rootSectionTitle,
        String targetSectionHint,
        Long structureNodeId,
        String canonicalPath,
        Integer itemIndex,
        String retrievalQuestion
    ) {
        private static PreviousNavigationSnapshot empty() {
            return new PreviousNavigationSnapshot(null, "", "", "", "", "", null, "", null, "");
        }

        private static PreviousNavigationSnapshot fromNavigationDecision(Long exchangeId, DocumentNavigationDecision navigationDecision) {
            return new PreviousNavigationSnapshot(
                exchangeId,
                navigationDecision == null || navigationDecision.getSubjectAnchor() == null
                    ? ""
                    : safe(navigationDecision.getSubjectAnchor().getAnchorText()),
                navigationDecision == null || navigationDecision.getTopicAnchor() == null
                    ? ""
                    : safe(navigationDecision.getTopicAnchor().getFacet()),
                navigationDecision == null || navigationDecision.getStructureAnchor() == null
                    ? ""
                    : safe(navigationDecision.getStructureAnchor().getRootSectionCode()),
                navigationDecision == null || navigationDecision.getStructureAnchor() == null
                    ? ""
                    : safe(navigationDecision.getStructureAnchor().getRootSectionTitle()),
                navigationDecision == null || navigationDecision.getStructureAnchor() == null
                    ? ""
                    : safe(navigationDecision.getStructureAnchor().getTargetSectionHint()),
                navigationDecision == null || navigationDecision.getStructureAnchor() == null
                    ? null
                    : navigationDecision.getStructureAnchor().getStructureNodeId(),
                navigationDecision == null || navigationDecision.getStructureAnchor() == null
                    ? ""
                    : safe(navigationDecision.getStructureAnchor().getCanonicalPath()),
                navigationDecision == null || navigationDecision.getItemAnchor() == null
                    ? null
                    : navigationDecision.getItemAnchor().getItemIndex(),
                navigationDecision == null || navigationDecision.getRetrievalPlan() == null ? "" : safe(navigationDecision.getRetrievalPlan().getRetrievalQuestion())
            );
        }

        private boolean isEmpty() {
            return StrUtil.isBlank(subjectText)
                && StrUtil.isBlank(topicText)
                && StrUtil.isBlank(rootSectionCode)
                && StrUtil.isBlank(rootSectionTitle)
                && StrUtil.isBlank(targetSectionHint)
                && structureNodeId == null
                && StrUtil.isBlank(canonicalPath)
                && itemIndex == null
                && StrUtil.isBlank(retrievalQuestion);
        }

        private static String safe(String text) {
            return text == null ? "" : text.trim();
        }
    }

    private record ItemResolution(
        Integer referencedItemIndex,
        String referencedItemText,
        Long structureNodeId,
        String canonicalPath,
        List<Integer> itemIndexes,
        boolean exactItemRequired
    ) {
        private static ItemResolution empty() {
            return new ItemResolution(null, "", null, "", List.of(), false);
        }

        private static ItemResolution exactItem(SuperAgentDocumentStructureNode node) {
            return new ItemResolution(
                node == null ? null : node.getItemIndex(),
                node == null ? "" : safeNodeText(node),
                node == null ? null : node.getId(),
                node == null ? "" : node.getCanonicalPath(),
                node == null || node.getItemIndex() == null ? List.of() : List.of(node.getItemIndex()),
                true
            );
        }

        private static ItemResolution itemIndexes(List<Integer> itemIndexes) {
            return new ItemResolution(null, "", null, "", itemIndexes == null ? List.of() : itemIndexes, false);
        }

        private static String safeNodeText(SuperAgentDocumentStructureNode node) {
            if (node == null) {
                return "";
            }
            String title = node.getAnchorText();
            if (StrUtil.isNotBlank(title)) {
                return title.trim();
            }
            return node.getContentText() == null ? "" : node.getContentText().trim();
        }
    }

    private record ScopeResolution(
        NavigationScopeMode scopeMode,
        String targetSectionHint,
        List<String> strictSectionHints,
        List<String> strictCanonicalPathHints,
        List<Long> strictStructureNodeIds,
        List<Integer> strictItemIndexes,
        List<String> softSectionHints,
        List<String> queryContextHints,
        boolean missingRequestedStructure
    ) {
        private static Builder builder() {
            return new Builder();
        }

        private static class Builder {
            private NavigationScopeMode scopeMode = NavigationScopeMode.NONE;
            private String targetSectionHint = "";
            private List<String> strictSectionHints = List.of();
            private List<String> strictCanonicalPathHints = List.of();
            private List<Long> strictStructureNodeIds = List.of();
            private List<Integer> strictItemIndexes = List.of();
            private List<String> softSectionHints = List.of();
            private List<String> queryContextHints = List.of();
            private boolean missingRequestedStructure;

            private Builder scopeMode(NavigationScopeMode scopeMode) {
                this.scopeMode = scopeMode;
                return this;
            }

            private Builder targetSectionHint(String targetSectionHint) {
                this.targetSectionHint = targetSectionHint == null ? "" : targetSectionHint;
                return this;
            }

            private Builder strictSectionHints(List<String> strictSectionHints) {
                this.strictSectionHints = strictSectionHints == null ? List.of() : strictSectionHints;
                return this;
            }

            private Builder strictCanonicalPathHints(List<String> strictCanonicalPathHints) {
                this.strictCanonicalPathHints = strictCanonicalPathHints == null ? List.of() : strictCanonicalPathHints;
                return this;
            }

            private Builder strictStructureNodeIds(List<Long> strictStructureNodeIds) {
                this.strictStructureNodeIds = strictStructureNodeIds == null ? List.of() : strictStructureNodeIds;
                return this;
            }

            private Builder strictItemIndexes(List<Integer> strictItemIndexes) {
                this.strictItemIndexes = strictItemIndexes == null ? List.of() : strictItemIndexes;
                return this;
            }

            private Builder softSectionHints(List<String> softSectionHints) {
                this.softSectionHints = softSectionHints == null ? List.of() : softSectionHints;
                return this;
            }

            private Builder queryContextHints(List<String> queryContextHints) {
                this.queryContextHints = queryContextHints == null ? List.of() : queryContextHints;
                return this;
            }

            private Builder missingRequestedStructure(boolean missingRequestedStructure) {
                this.missingRequestedStructure = missingRequestedStructure;
                return this;
            }

            private ScopeResolution build() {
                return new ScopeResolution(
                    scopeMode,
                    targetSectionHint,
                    strictSectionHints,
                    strictCanonicalPathHints,
                    strictStructureNodeIds,
                    strictItemIndexes,
                    softSectionHints,
                    queryContextHints,
                    missingRequestedStructure
                );
            }
        }
    }
}
