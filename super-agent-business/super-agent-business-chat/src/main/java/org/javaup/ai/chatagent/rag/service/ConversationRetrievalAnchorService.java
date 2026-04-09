package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.model.debug.ChatDebugTrace;
import org.javaup.ai.chatagent.rag.model.ConversationIntentRelationType;
import org.javaup.ai.chatagent.rag.model.ConversationIntentResolution;
import org.javaup.ai.chatagent.rag.model.RagRewriteResult;
import org.javaup.ai.chatagent.rag.model.RetrievalAnchorContext;
import org.javaup.ai.chatagent.rag.model.RetrievalAnchorResolution;
import org.javaup.ai.chatagent.service.ConversationArchiveStore;
import org.javaup.enums.ChatTurnStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多轮追问检索锚点服务。
 *
 * <p>它专门解决一个在教学项目里非常典型的问题：</p>
 * <p>回答阶段已经能“看懂这是在追问上文”，
 * 但检索阶段如果仍然只拿“这个问题/那个问题/第三条怎么排查”去查，
 * 召回就会明显跑偏。</p>
 *
 * <p>因此这层的职责不是生成最终回答，而是：</p>
 * <p>1. 从最近完成轮次中恢复当前会话的检索锚点。</p>
 * <p>2. 判断当前这轮是不是承接式追问。</p>
 * <p>3. 在需要时把模糊追问改写成可检索的明确问题。</p>
 * <p>4. 同时产出章节提示和上下文提示，供检索层进一步使用。</p>
 */
@Slf4j
@Service
public class ConversationRetrievalAnchorService {

    private static final int RECENT_EXCHANGE_LIMIT = 8;

    private static final Pattern ROOT_SECTION_PATTERN = Pattern.compile("^(\\d+\\.\\d+)\\s+(.+)$");
    private static final Pattern FACET_SECTION_PATTERN = Pattern.compile("^(\\d+\\.\\d+\\.\\d+)\\s+(.+)$");
    private static final Pattern ORDINAL_PATTERN = Pattern.compile("第\\s*([0-9一二三四五六七八九十百]+)\\s*(条|点|项|个)");
    private static final Pattern NUMBERED_ITEM_PATTERN = Pattern.compile("^\\s*(\\d+)\\.\\s*(.+)$");
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\s*\\[[0-9]+](?:\\[[0-9]+])*$");
    private static final Pattern EXPLICIT_TOPIC_PATTERN = Pattern.compile("^(?:那如果是|如果是|如果换成|换成|对于|关于)(.+?)这个问题[，,]?(.*)$");

    private static final Set<String> FOLLOW_UP_HINTS = Set.of(
        "这个", "那个", "这个问题", "那个问题", "上面", "前面", "刚才", "之前", "继续", "还有", "那", "那么"
    );

    private static final Set<String> FACET_PHENOMENON_HINTS = Set.of("现象", "表现", "症状", "表现形式");
    private static final Set<String> FACET_CAUSE_HINTS = Set.of("原因", "成因", "诱因", "为什么");
    private static final Set<String> FACET_HANDLING_HINTS = Set.of("处理步骤", "处理", "排查", "怎么排查", "如何排查", "怎么处理", "如何处理", "检查顺序");

    private final ConversationArchiveStore conversationArchiveStore;
    private final ConversationIntentResolutionService conversationIntentResolutionService;

    public ConversationRetrievalAnchorService(ConversationArchiveStore conversationArchiveStore,
                                              ConversationIntentResolutionService conversationIntentResolutionService) {
        this.conversationArchiveStore = conversationArchiveStore;
        this.conversationIntentResolutionService = conversationIntentResolutionService;
    }

    /**
     * 解析当前轮的检索锚点。
     */
    public RetrievalAnchorResolution resolve(String conversationId,
                                             String question,
                                             RagRewriteResult rewriteResult) {
        String normalizedQuestion = safeText(question);
        RagRewriteResult normalizedRewriteResult = normalizeRewriteResult(normalizedQuestion, rewriteResult);
        ConversationExchangeView anchorExchange = findLatestCompletedExchange(conversationId);
        if (anchorExchange == null) {
            log.info("检索锚点解析: conversationId={}, question='{}', followUp=false, reason=no_completed_anchor_exchange",
                conversationId, normalizedQuestion);
            return new RetrievalAnchorResolution(normalizedRewriteResult, emptyContext());
        }

        AnchorSeed anchorSeed = buildAnchorSeed(anchorExchange);
        List<ConversationExchangeView> recentCompletedExchanges = listRecentCompletedExchanges(conversationId);
        ConversationIntentResolution intentResolution = conversationIntentResolutionService.resolve(
            normalizedQuestion,
            normalizedRewriteResult,
            recentCompletedExchanges,
            buildPreviousAnchorDescription(anchorSeed)
        );
        if (intentResolution != null && intentResolution.confident(0.60D)) {
            RetrievalAnchorResolution resolvedByIntent = resolveByIntent(anchorExchange, anchorSeed, normalizedQuestion, normalizedRewriteResult, intentResolution);
            if (resolvedByIntent != null) {
                return resolvedByIntent;
            }
        }

        String explicitTopic = extractExplicitTopic(normalizedQuestion);
        if (shouldSwitchToExplicitTopic(explicitTopic, anchorSeed)) {
            RetrievalAnchorContext explicitTopicContext = buildExplicitTopicContext(
                normalizedQuestion,
                explicitTopic,
                anchorExchange,
                anchorSeed
            );
            RagRewriteResult effectiveRewriteResult = buildEffectiveRewriteResult(
                normalizedQuestion,
                normalizedRewriteResult,
                explicitTopicContext
            );
            log.info("检索锚点解析: conversationId={}, question='{}', followUp=false, explicitTopicSwitch=true, explicitTopic='{}', targetFacet='{}', targetSectionHint='{}', effectiveRewrite='{}'",
                conversationId,
                normalizedQuestion,
                explicitTopicContext.getRootTopic(),
                explicitTopicContext.getTargetFacet(),
                explicitTopicContext.getTargetSectionHint(),
                effectiveRewriteResult.getRewrittenQuestion());
            return new RetrievalAnchorResolution(effectiveRewriteResult, explicitTopicContext);
        }
        boolean followUpQuestion = looksLikeFollowUpQuestion(normalizedQuestion, anchorSeed);
        if (!followUpQuestion) {
            log.info("检索锚点解析: conversationId={}, question='{}', followUp=false, anchorExchangeId={}, anchorSource='{}', rootTopic='{}'",
                conversationId,
                normalizedQuestion,
                anchorExchange.getExchangeId(),
                anchorSeed.anchorSourceQuestion(),
                anchorSeed.rootTopic());
            return new RetrievalAnchorResolution(normalizedRewriteResult, emptyContext());
        }

        String targetFacet = resolveTargetFacet(normalizedQuestion, anchorSeed.currentFacet());
        Integer referencedItemIndex = resolveReferencedItemIndex(normalizedQuestion);
        String referencedItemText = resolveReferencedItemText(referencedItemIndex, anchorSeed.enumeratedItems());
        String targetSectionHint = buildTargetSectionHint(anchorSeed.rootSectionCode(), targetFacet, anchorSeed.currentFacet());
        String resolvedQuestion = buildResolvedQuestion(
            normalizedQuestion,
            anchorSeed,
            targetFacet,
            referencedItemText
        );

        RetrievalAnchorContext anchorContext = RetrievalAnchorContext.builder()
            .followUpQuestion(true)
            .anchorApplied(StrUtil.isNotBlank(resolvedQuestion))
            .anchorExchangeId(anchorExchange.getExchangeId())
            .anchorSourceQuestion(anchorSeed.anchorSourceQuestion())
            .rootTopic(anchorSeed.rootTopic())
            .rootSectionCode(anchorSeed.rootSectionCode())
            .rootSectionTitle(anchorSeed.rootSectionTitle())
            .targetFacet(targetFacet)
            .targetSectionHint(targetSectionHint)
            .referencedItemIndex(referencedItemIndex)
            .referencedItemText(referencedItemText)
            .resolvedQuestion(resolvedQuestion)
            .queryContextHints(buildQueryContextHints(anchorSeed, targetFacet, referencedItemText))
            .softSectionHints(buildSectionHints(anchorSeed, targetSectionHint))
            .strictSectionHints(List.of())
            .build();

        RagRewriteResult effectiveRewriteResult = buildEffectiveRewriteResult(
            normalizedQuestion,
            normalizedRewriteResult,
            anchorContext
        );
        log.info("检索锚点解析: conversationId={}, question='{}', followUp=true, anchorApplied={}, anchorExchangeId={}, rootTopic='{}', rootSectionCode='{}', targetFacet='{}', targetSectionHint='{}', itemIndex={}, itemText='{}', effectiveRewrite='{}'",
            conversationId,
            normalizedQuestion,
            anchorContext.isAnchorApplied(),
            anchorContext.getAnchorExchangeId(),
            anchorContext.getRootTopic(),
            anchorContext.getRootSectionCode(),
            anchorContext.getTargetFacet(),
            anchorContext.getTargetSectionHint(),
            anchorContext.getReferencedItemIndex(),
            anchorContext.getReferencedItemText(),
            effectiveRewriteResult.getRewrittenQuestion());
        return new RetrievalAnchorResolution(effectiveRewriteResult, anchorContext);
    }

    private RetrievalAnchorResolution resolveByIntent(ConversationExchangeView anchorExchange,
                                                      AnchorSeed anchorSeed,
                                                      String question,
                                                      RagRewriteResult rewriteResult,
                                                      ConversationIntentResolution intentResolution) {
        ConversationIntentRelationType relationType = intentResolution.getRelationType();
        if (relationType == null || relationType == ConversationIntentRelationType.UNKNOWN) {
            return null;
        }
        return switch (relationType) {
            case FOLLOW_UP -> buildFollowUpResolution(anchorExchange, anchorSeed, question, rewriteResult, intentResolution);
            case TOPIC_SWITCH, FRESH_TOPIC -> buildFreshOrSwitchResolution(anchorExchange, anchorSeed, question, rewriteResult, intentResolution);
            default -> null;
        };
    }

    private RetrievalAnchorResolution buildFollowUpResolution(ConversationExchangeView anchorExchange,
                                                              AnchorSeed anchorSeed,
                                                              String question,
                                                              RagRewriteResult rewriteResult,
                                                              ConversationIntentResolution intentResolution) {
        String targetFacet = StrUtil.blankToDefault(safeText(intentResolution.getResolvedFacet()), resolveTargetFacet(question, anchorSeed.currentFacet()));
        Integer referencedItemIndex = intentResolution.getReferencedItemIndex() != null
            ? intentResolution.getReferencedItemIndex()
            : resolveReferencedItemIndex(question);
        String referencedItemText = resolveReferencedItemText(referencedItemIndex, anchorSeed.enumeratedItems());
        String targetSectionHint = resolveTargetSectionHint(intentResolution, anchorSeed, targetFacet);
        String resolvedQuestion = resolveRetrievalQuery(intentResolution, anchorSeed, question, targetFacet, referencedItemText);
        List<String> strictSectionHints = buildStrictFollowUpSectionHints(anchorSeed, targetFacet);
        RetrievalAnchorContext anchorContext = RetrievalAnchorContext.builder()
            .followUpQuestion(true)
            .anchorApplied(StrUtil.isNotBlank(resolvedQuestion))
            .anchorExchangeId(anchorExchange.getExchangeId())
            .anchorSourceQuestion(anchorSeed.anchorSourceQuestion())
            .rootTopic(anchorSeed.rootTopic())
            .rootSectionCode(anchorSeed.rootSectionCode())
            .rootSectionTitle(anchorSeed.rootSectionTitle())
            .targetFacet(targetFacet)
            .targetSectionHint(targetSectionHint)
            .referencedItemIndex(referencedItemIndex)
            .referencedItemText(referencedItemText)
            .resolvedQuestion(resolvedQuestion)
            .queryContextHints(resolveQueryContextHints(intentResolution, anchorSeed, targetFacet, referencedItemText))
            .softSectionHints(resolveSectionHints(intentResolution, anchorSeed, targetSectionHint))
            .strictSectionHints(strictSectionHints)
            .build();
        RagRewriteResult effectiveRewriteResult = buildEffectiveRewriteResult(question, rewriteResult, anchorContext);
        log.info("检索锚点解析: anchorSource='{}', question='{}', followUp=true, llmRelation={}, anchorApplied={}, anchorExchangeId={}, rootTopic='{}', rootSectionCode='{}', targetFacet='{}', targetSectionHint='{}', itemIndex={}, itemText='{}', effectiveRewrite='{}'",
            anchorSeed.anchorSourceQuestion(),
            question,
            intentResolution.getRelationType(),
            anchorContext.isAnchorApplied(),
            anchorContext.getAnchorExchangeId(),
            anchorContext.getRootTopic(),
            anchorContext.getRootSectionCode(),
            anchorContext.getTargetFacet(),
            anchorContext.getTargetSectionHint(),
            anchorContext.getReferencedItemIndex(),
            anchorContext.getReferencedItemText(),
            effectiveRewriteResult.getRewrittenQuestion());
        return new RetrievalAnchorResolution(effectiveRewriteResult, anchorContext);
    }

    private RetrievalAnchorResolution buildFreshOrSwitchResolution(ConversationExchangeView anchorExchange,
                                                                  AnchorSeed anchorSeed,
                                                                  String question,
                                                                  RagRewriteResult rewriteResult,
                                                                  ConversationIntentResolution intentResolution) {
        String resolvedTopic = StrUtil.blankToDefault(safeText(intentResolution.getResolvedTopic()), safeText(rewriteResult.getRewrittenQuestion()));
        String targetFacet = safeText(intentResolution.getResolvedFacet());
        RetrievalAnchorContext explicitTopicContext = buildFreshTopicContext(
            anchorExchange,
            anchorSeed,
            resolvedTopic,
            targetFacet,
            intentResolution
        );
        RagRewriteResult effectiveRewriteResult = buildEffectiveRewriteResult(question, rewriteResult, explicitTopicContext);
        log.info("检索锚点解析: anchorSource='{}', question='{}', followUp=false, llmRelation={}, anchorApplied={}, resolvedTopic='{}', targetFacet='{}', targetSectionHint='{}', effectiveRewrite='{}'",
            anchorSeed.anchorSourceQuestion(),
            question,
            intentResolution.getRelationType(),
            explicitTopicContext.isAnchorApplied(),
            explicitTopicContext.getRootTopic(),
            explicitTopicContext.getTargetFacet(),
            explicitTopicContext.getTargetSectionHint(),
            effectiveRewriteResult.getRewrittenQuestion());
        return new RetrievalAnchorResolution(effectiveRewriteResult, explicitTopicContext);
    }

    private ConversationExchangeView findLatestCompletedExchange(String conversationId) {
        if (StrUtil.isBlank(conversationId)) {
            return null;
        }
        List<ConversationExchangeView> recentExchanges = conversationArchiveStore.listRecentExchanges(conversationId, RECENT_EXCHANGE_LIMIT);
        for (int index = recentExchanges.size() - 1; index >= 0; index--) {
            ConversationExchangeView exchange = recentExchanges.get(index);
            if (exchange == null
                || exchange.getStatus() != ChatTurnStatus.COMPLETED
                || StrUtil.isBlank(exchange.getQuestion())
                || StrUtil.isBlank(exchange.getAnswer())) {
                continue;
            }
            return exchange;
        }
        return null;
    }

    private List<ConversationExchangeView> listRecentCompletedExchanges(String conversationId) {
        if (StrUtil.isBlank(conversationId)) {
            return List.of();
        }
        return conversationArchiveStore.listRecentExchanges(conversationId, RECENT_EXCHANGE_LIMIT).stream()
            .filter(exchange -> exchange != null
                && exchange.getStatus() == ChatTurnStatus.COMPLETED
                && StrUtil.isNotBlank(exchange.getQuestion())
                && StrUtil.isNotBlank(exchange.getAnswer()))
            .toList();
    }

    private AnchorSeed buildAnchorSeed(ConversationExchangeView exchange) {
        ChatDebugTrace debugTrace = exchange.getDebugTrace();
        List<String> enumeratedItems = extractEnumeratedItems(exchange.getAnswer());

        String rootTopic = debugTrace == null ? "" : safeText(debugTrace.getRetrievalAnchorRootTopic());
        String rootSectionCode = debugTrace == null ? "" : safeText(debugTrace.getRetrievalAnchorRootSectionCode());
        String rootSectionTitle = debugTrace == null ? "" : safeText(debugTrace.getRetrievalAnchorRootSectionTitle());
        String currentFacet = debugTrace == null ? "" : safeText(debugTrace.getRetrievalAnchorFacet());
        String currentSectionHint = debugTrace == null ? "" : safeText(debugTrace.getRetrievalAnchorTargetSectionHint());
        String anchorSourceQuestion = debugTrace == null ? "" : safeText(debugTrace.getRetrievalAnchorResolvedQuestion());

        if (StrUtil.isBlank(anchorSourceQuestion)) {
            anchorSourceQuestion = debugTrace == null ? "" : safeText(debugTrace.getRewrittenQuestion());
        }
        if (StrUtil.isBlank(anchorSourceQuestion)) {
            anchorSourceQuestion = safeText(exchange.getQuestion());
        }

        if (StrUtil.isBlank(rootTopic) || StrUtil.isBlank(rootSectionCode)) {
            SectionAnchor sectionAnchor = deriveSectionAnchorFromReferences(exchange.getReferences());
            if (sectionAnchor != null) {
                if (StrUtil.isBlank(rootTopic)) {
                    rootTopic = sectionAnchor.rootTopic();
                }
                if (StrUtil.isBlank(rootSectionCode)) {
                    rootSectionCode = sectionAnchor.rootSectionCode();
                }
                if (StrUtil.isBlank(rootSectionTitle)) {
                    rootSectionTitle = sectionAnchor.rootSectionTitle();
                }
                if (StrUtil.isBlank(currentFacet)) {
                    currentFacet = sectionAnchor.facetTitle();
                }
                if (StrUtil.isBlank(currentSectionHint)) {
                    currentSectionHint = sectionAnchor.facetSectionTitle();
                }
            }
        }

        if (StrUtil.isBlank(rootTopic) && StrUtil.isNotBlank(anchorSourceQuestion)) {
            rootTopic = anchorSourceQuestion;
        }

        return new AnchorSeed(
            exchange.getExchangeId(),
            anchorSourceQuestion,
            rootTopic,
            rootSectionCode,
            rootSectionTitle,
            currentFacet,
            currentSectionHint,
            enumeratedItems
        );
    }

    private SectionAnchor deriveSectionAnchorFromReferences(List<SearchReference> references) {
        if (references == null || references.isEmpty()) {
            return null;
        }
        Map<String, SectionAnchor> anchorMap = new LinkedHashMap<>();
        Map<String, Integer> scoreMap = new LinkedHashMap<>();
        for (SearchReference reference : references) {
            if (reference == null || !"DOCUMENT".equalsIgnoreCase(reference.getSourceType())) {
                continue;
            }
            SectionAnchor anchor = parseSectionAnchor(reference.getSectionPath());
            if (anchor == null || StrUtil.isBlank(anchor.rootSectionCode())) {
                continue;
            }
            anchorMap.putIfAbsent(anchor.rootSectionCode(), anchor);
            scoreMap.merge(anchor.rootSectionCode(), 1, Integer::sum);
        }
        String bestRootCode = null;
        int bestScore = 0;
        for (Map.Entry<String, Integer> entry : scoreMap.entrySet()) {
            if (entry.getValue() > bestScore) {
                bestRootCode = entry.getKey();
                bestScore = entry.getValue();
            }
        }
        if (bestRootCode == null) {
            return null;
        }
        return anchorMap.get(bestRootCode);
    }

    private SectionAnchor parseSectionAnchor(String sectionPath) {
        String normalized = safeText(sectionPath);
        if (normalized.isBlank()) {
            return null;
        }
        String rootSectionCode = "";
        String rootSectionTitle = "";
        String rootTopic = "";
        String facetSectionTitle = "";
        String facetTitle = "";
        for (String rawSegment : normalized.split("\\s*>\\s*")) {
            String segment = safeText(rawSegment);
            Matcher rootMatcher = ROOT_SECTION_PATTERN.matcher(segment);
            if (rootMatcher.matches()) {
                String code = rootMatcher.group(1);
                if (code.split("\\.").length == 2) {
                    rootSectionCode = code;
                    rootSectionTitle = segment;
                    rootTopic = extractRootTopic(segment);
                }
            }
            Matcher facetMatcher = FACET_SECTION_PATTERN.matcher(segment);
            if (facetMatcher.matches()) {
                String code = facetMatcher.group(1);
                if (code.split("\\.").length == 3) {
                    facetSectionTitle = segment;
                    facetTitle = stripLeadingSectionCode(segment);
                }
            }
        }
        if (rootSectionCode.isBlank() && facetSectionTitle.isBlank()) {
            return null;
        }
        return new SectionAnchor(rootSectionCode, rootSectionTitle, rootTopic, facetSectionTitle, facetTitle);
    }

    private boolean looksLikeFollowUpQuestion(String question, AnchorSeed anchorSeed) {
        if (StrUtil.isBlank(question) || anchorSeed == null || StrUtil.isBlank(anchorSeed.rootTopic())) {
            return false;
        }
        if (FOLLOW_UP_HINTS.stream().anyMatch(question::contains)) {
            return true;
        }
        if (resolveReferencedItemIndex(question) != null) {
            return true;
        }
        if (StrUtil.isNotBlank(resolveTargetFacet(question, anchorSeed.currentFacet())) && question.length() <= 24) {
            return true;
        }
        return question.length() <= 16;
    }

    private String resolveTargetFacet(String question, String currentFacet) {
        String normalized = safeText(question);
        if (normalized.isBlank()) {
            return safeText(currentFacet);
        }
        if (normalized.contains("检查顺序")) {
            return "检查顺序";
        }
        if (FACET_PHENOMENON_HINTS.stream().anyMatch(normalized::contains)) {
            return "现象";
        }
        if (FACET_CAUSE_HINTS.stream().anyMatch(normalized::contains)) {
            return "可能原因";
        }
        if (FACET_HANDLING_HINTS.stream().anyMatch(normalized::contains)) {
            return "处理步骤";
        }
        return safeText(currentFacet);
    }

    private Integer resolveReferencedItemIndex(String question) {
        String normalized = safeText(question);
        if (normalized.isBlank()) {
            return null;
        }
        Matcher matcher = ORDINAL_PATTERN.matcher(normalized);
        if (matcher.find()) {
            return parseChineseNumber(matcher.group(1));
        }
        return null;
    }

    private String resolveReferencedItemText(Integer itemIndex, List<String> enumeratedItems) {
        if (itemIndex == null || enumeratedItems == null || itemIndex <= 0 || itemIndex > enumeratedItems.size()) {
            return "";
        }
        return safeText(enumeratedItems.get(itemIndex - 1));
    }

    private String buildTargetSectionHint(String rootSectionCode, String targetFacet, String currentSectionHint) {
        if (StrUtil.isBlank(targetFacet)) {
            return safeText(currentSectionHint);
        }
        if (StrUtil.isBlank(rootSectionCode)) {
            return targetFacet;
        }
        return switch (targetFacet) {
            case "现象" -> rootSectionCode + ".1 现象";
            case "可能原因" -> rootSectionCode + ".2 可能原因";
            case "处理步骤" -> rootSectionCode + ".3 处理步骤";
            case "检查顺序" -> targetFacet;
            default -> targetFacet;
        };
    }

    private String buildResolvedQuestion(String currentQuestion,
                                         AnchorSeed anchorSeed,
                                         String targetFacet,
                                         String referencedItemText) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(anchorSeed.rootTopic())) {
            parts.add(anchorSeed.rootTopic());
        } else if (StrUtil.isNotBlank(anchorSeed.anchorSourceQuestion())) {
            parts.add(anchorSeed.anchorSourceQuestion());
        }
        if (StrUtil.isNotBlank(targetFacet)) {
            parts.add(targetFacet);
        }
        if (StrUtil.isNotBlank(referencedItemText)) {
            parts.add(referencedItemText);
        }
        if (currentQuestion.contains("区别") || currentQuestion.contains("差异")) {
            parts.add("区别");
        }
        return String.join(" ", parts).trim();
    }

    private List<String> buildQueryContextHints(AnchorSeed anchorSeed,
                                                String targetFacet,
                                                String referencedItemText) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(anchorSeed.rootTopic())) {
            hints.add(anchorSeed.rootTopic());
        }
        if (StrUtil.isNotBlank(targetFacet)) {
            hints.add(targetFacet);
        }
        if (StrUtil.isNotBlank(referencedItemText)) {
            hints.add(referencedItemText);
        }
        if (StrUtil.isNotBlank(anchorSeed.rootSectionTitle())) {
            hints.add(anchorSeed.rootSectionTitle());
        }
        return new ArrayList<>(hints);
    }

    private List<String> buildSectionHints(AnchorSeed anchorSeed, String targetSectionHint) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(targetSectionHint)) {
            hints.add(targetSectionHint);
        }
        if (StrUtil.isNotBlank(anchorSeed.currentSectionHint())) {
            hints.add(anchorSeed.currentSectionHint());
        }
        if (StrUtil.isNotBlank(anchorSeed.rootSectionTitle())) {
            hints.add(anchorSeed.rootSectionTitle());
        }
        return new ArrayList<>(hints);
    }

    private String resolveTargetSectionHint(ConversationIntentResolution intentResolution,
                                            AnchorSeed anchorSeed,
                                            String targetFacet) {
        if (anchorSeed != null && StrUtil.isNotBlank(anchorSeed.rootSectionCode()) && StrUtil.isNotBlank(targetFacet)) {
            return buildTargetSectionHint(anchorSeed.rootSectionCode(), targetFacet, anchorSeed.currentFacet());
        }
        if (intentResolution != null && intentResolution.getSoftSectionHints() != null && !intentResolution.getSoftSectionHints().isEmpty()) {
            return intentResolution.getSoftSectionHints().get(0);
        }
        return buildTargetSectionHint(anchorSeed.rootSectionCode(), targetFacet, anchorSeed.currentFacet());
    }

    private String resolveRetrievalQuery(ConversationIntentResolution intentResolution,
                                         AnchorSeed anchorSeed,
                                         String currentQuestion,
                                         String targetFacet,
                                         String referencedItemText) {
        if (intentResolution != null && StrUtil.isNotBlank(intentResolution.getRetrievalQuery())) {
            return intentResolution.getRetrievalQuery().trim();
        }
        return buildResolvedQuestion(currentQuestion, anchorSeed, targetFacet, referencedItemText);
    }

    private List<String> resolveQueryContextHints(ConversationIntentResolution intentResolution,
                                                  AnchorSeed anchorSeed,
                                                  String targetFacet,
                                                  String referencedItemText) {
        if (intentResolution != null && intentResolution.getQueryContextHints() != null && !intentResolution.getQueryContextHints().isEmpty()) {
            return new ArrayList<>(intentResolution.getQueryContextHints());
        }
        return buildQueryContextHints(anchorSeed, targetFacet, referencedItemText);
    }

    private List<String> resolveSectionHints(ConversationIntentResolution intentResolution,
                                             AnchorSeed anchorSeed,
                                             String targetSectionHint) {
        if (intentResolution != null && intentResolution.getSoftSectionHints() != null && !intentResolution.getSoftSectionHints().isEmpty()) {
            return new ArrayList<>(intentResolution.getSoftSectionHints());
        }
        return buildSectionHints(anchorSeed, targetSectionHint);
    }

    private String resolveFreshRetrievalQuery(ConversationIntentResolution intentResolution,
                                              String topic,
                                              String targetFacet) {
        if (intentResolution != null && StrUtil.isNotBlank(intentResolution.getRetrievalQuery())) {
            return intentResolution.getRetrievalQuery().trim();
        }
        return StrUtil.isBlank(targetFacet) ? topic : (topic + " " + targetFacet).trim();
    }

    private List<String> resolveFreshQueryContextHints(ConversationIntentResolution intentResolution,
                                                       String topic,
                                                       String targetFacet) {
        if (intentResolution != null && intentResolution.getQueryContextHints() != null && !intentResolution.getQueryContextHints().isEmpty()) {
            return new ArrayList<>(intentResolution.getQueryContextHints());
        }
        LinkedHashSet<String> queryHints = new LinkedHashSet<>();
        queryHints.add(topic);
        if (StrUtil.isNotBlank(targetFacet)) {
            queryHints.add(targetFacet);
        }
        return new ArrayList<>(queryHints);
    }

    private List<String> resolveFreshSectionHints(ConversationIntentResolution intentResolution,
                                                  String targetFacet) {
        if (intentResolution != null && intentResolution.getSoftSectionHints() != null && !intentResolution.getSoftSectionHints().isEmpty()) {
            return new ArrayList<>(intentResolution.getSoftSectionHints());
        }
        if (StrUtil.isBlank(targetFacet)) {
            return List.of();
        }
        return List.of(targetFacet);
    }

    private String buildPreviousAnchorDescription(AnchorSeed anchorSeed) {
        if (anchorSeed == null) {
            return "无";
        }
        return "rootTopic=" + StrUtil.blankToDefault(anchorSeed.rootTopic(), "")
            + "; rootSectionCode=" + StrUtil.blankToDefault(anchorSeed.rootSectionCode(), "")
            + "; rootSectionTitle=" + StrUtil.blankToDefault(anchorSeed.rootSectionTitle(), "")
            + "; currentFacet=" + StrUtil.blankToDefault(anchorSeed.currentFacet(), "")
            + "; currentSectionHint=" + StrUtil.blankToDefault(anchorSeed.currentSectionHint(), "")
            + "; anchorSourceQuestion=" + StrUtil.blankToDefault(anchorSeed.anchorSourceQuestion(), "");
    }

    private String extractExplicitTopic(String question) {
        String normalized = safeText(question);
        if (normalized.isBlank()) {
            return "";
        }
        Matcher matcher = EXPLICIT_TOPIC_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return "";
        }
        return safeText(matcher.group(1));
    }

    private boolean shouldSwitchToExplicitTopic(String explicitTopic, AnchorSeed anchorSeed) {
        if (StrUtil.isBlank(explicitTopic)) {
            return false;
        }
        if (anchorSeed == null || StrUtil.isBlank(anchorSeed.rootTopic())) {
            return true;
        }
        String normalizedExplicit = explicitTopic.replaceAll("\\s+", "");
        String normalizedAnchor = safeText(anchorSeed.rootTopic()).replaceAll("\\s+", "");
        if (normalizedExplicit.isBlank()) {
            return false;
        }
        return !normalizedExplicit.equals(normalizedAnchor)
            && !normalizedExplicit.contains(normalizedAnchor)
            && !normalizedAnchor.contains(normalizedExplicit);
    }

    private RetrievalAnchorContext buildExplicitTopicContext(String question,
                                                             String explicitTopic,
                                                             ConversationExchangeView anchorExchange,
                                                             AnchorSeed anchorSeed) {
        String targetFacet = resolveTargetFacet(question, "");
        return buildFreshTopicContext(
            anchorExchange,
            anchorSeed,
            explicitTopic,
            targetFacet,
            ConversationIntentResolution.builder()
                .resolvedTopic(explicitTopic)
                .resolvedFacet(targetFacet)
                .retrievalQuery(StrUtil.isBlank(targetFacet) ? explicitTopic : (explicitTopic + " " + targetFacet).trim())
                .softSectionHints(StrUtil.isBlank(targetFacet) ? List.of() : List.of(targetFacet))
                .queryContextHints(StrUtil.isBlank(targetFacet) ? List.of(explicitTopic) : List.of(explicitTopic, targetFacet))
                .build()
        );
    }

    private RetrievalAnchorContext buildFreshTopicContext(ConversationExchangeView anchorExchange,
                                                          AnchorSeed anchorSeed,
                                                          String topic,
                                                          String targetFacet,
                                                          ConversationIntentResolution intentResolution) {
        String resolvedQuestion = resolveFreshRetrievalQuery(intentResolution, topic, targetFacet);
        List<String> queryHints = resolveFreshQueryContextHints(intentResolution, topic, targetFacet);
        List<String> sectionHints = resolveFreshSectionHints(intentResolution, targetFacet);
        return RetrievalAnchorContext.builder()
            .followUpQuestion(false)
            .anchorApplied(true)
            .anchorExchangeId(anchorExchange == null ? null : anchorExchange.getExchangeId())
            .anchorSourceQuestion(anchorSeed == null ? "" : anchorSeed.anchorSourceQuestion())
            .rootTopic(topic)
            .rootSectionCode("")
            .rootSectionTitle("")
            .targetFacet(targetFacet)
            .targetSectionHint(sectionHints.isEmpty() ? targetFacet : sectionHints.get(0))
            .referencedItemIndex(null)
            .referencedItemText("")
            .resolvedQuestion(resolvedQuestion)
            .queryContextHints(new ArrayList<>(queryHints))
            .softSectionHints(new ArrayList<>(sectionHints))
            .strictSectionHints(List.of())
            .build();
    }

    private List<String> buildStrictFollowUpSectionHints(AnchorSeed anchorSeed, String targetFacet) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (anchorSeed == null || StrUtil.isBlank(anchorSeed.rootSectionCode())) {
            return List.of();
        }
        hints.add(anchorSeed.rootSectionCode());
        if (StrUtil.isNotBlank(targetFacet)) {
            switch (targetFacet) {
                case "现象" -> hints.add(anchorSeed.rootSectionCode() + ".1");
                case "可能原因" -> hints.add(anchorSeed.rootSectionCode() + ".2");
                case "处理步骤" -> hints.add(anchorSeed.rootSectionCode() + ".3");
                default -> {
                }
            }
        }
        return new ArrayList<>(hints);
    }

    private RagRewriteResult buildEffectiveRewriteResult(String originalQuestion,
                                                         RagRewriteResult rewriteResult,
                                                         RetrievalAnchorContext anchorContext) {
        if (anchorContext == null || !anchorContext.isAnchorApplied() || StrUtil.isBlank(anchorContext.getResolvedQuestion())) {
            return rewriteResult;
        }
        return new RagRewriteResult(
            anchorContext.getResolvedQuestion(),
            List.of(anchorContext.getResolvedQuestion())
        );
    }

    private RagRewriteResult normalizeRewriteResult(String originalQuestion, RagRewriteResult rewriteResult) {
        if (rewriteResult == null) {
            return new RagRewriteResult(originalQuestion, List.of(originalQuestion));
        }
        String rewrittenQuestion = StrUtil.blankToDefault(safeText(rewriteResult.getRewrittenQuestion()), originalQuestion);
        List<String> subQuestions = rewriteResult.getSubQuestions() == null || rewriteResult.getSubQuestions().isEmpty()
            ? List.of(rewrittenQuestion)
            : rewriteResult.getSubQuestions();
        return new RagRewriteResult(rewrittenQuestion, subQuestions);
    }

    private RetrievalAnchorContext emptyContext() {
        return RetrievalAnchorContext.builder()
            .followUpQuestion(false)
            .anchorApplied(false)
            .queryContextHints(List.of())
            .softSectionHints(List.of())
            .strictSectionHints(List.of())
            .build();
    }

    private List<String> extractEnumeratedItems(String answer) {
        List<String> items = new ArrayList<>();
        if (StrUtil.isBlank(answer)) {
            return items;
        }
        for (String line : answer.split("\n")) {
            Matcher matcher = NUMBERED_ITEM_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String itemText = safeText(CITATION_PATTERN.matcher(matcher.group(2)).replaceFirst(""));
            itemText = itemText.replace("**", "").trim();
            if (StrUtil.isNotBlank(itemText)) {
                items.add(itemText);
            }
        }
        return items;
    }

    private int parseChineseNumber(String text) {
        if (StrUtil.isBlank(text)) {
            return 0;
        }
        if (text.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(text);
        }
        Map<Character, Integer> digitMap = Map.of(
            '一', 1, '二', 2, '三', 3, '四', 4, '五', 5,
            '六', 6, '七', 7, '八', 8, '九', 9
        );
        if ("十".equals(text)) {
            return 10;
        }
        if (text.startsWith("十")) {
            return 10 + digitMap.getOrDefault(text.charAt(1), 0);
        }
        if (text.endsWith("十")) {
            return digitMap.getOrDefault(text.charAt(0), 0) * 10;
        }
        if (text.contains("十") && text.length() == 3) {
            return digitMap.getOrDefault(text.charAt(0), 0) * 10 + digitMap.getOrDefault(text.charAt(2), 0);
        }
        return digitMap.getOrDefault(text.charAt(0), 0);
    }

    private String extractRootTopic(String rootSectionTitle) {
        String normalized = safeText(rootSectionTitle);
        if (normalized.isBlank()) {
            return "";
        }
        String withoutCode = normalized.replaceFirst("^\\d+\\.\\d+\\s+", "").trim();
        int colonIndex = Math.max(withoutCode.indexOf('：'), withoutCode.indexOf(':'));
        if (colonIndex >= 0 && colonIndex < withoutCode.length() - 1) {
            return withoutCode.substring(colonIndex + 1).trim();
        }
        return withoutCode;
    }

    private String stripLeadingSectionCode(String sectionTitle) {
        return safeText(sectionTitle).replaceFirst("^\\d+(?:\\.\\d+)+\\s+", "").trim();
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    private record AnchorSeed(
        long exchangeId,
        String anchorSourceQuestion,
        String rootTopic,
        String rootSectionCode,
        String rootSectionTitle,
        String currentFacet,
        String currentSectionHint,
        List<String> enumeratedItems
    ) {
    }

    private record SectionAnchor(
        String rootSectionCode,
        String rootSectionTitle,
        String rootTopic,
        String facetSectionTitle,
        String facetTitle
    ) {
    }

}
