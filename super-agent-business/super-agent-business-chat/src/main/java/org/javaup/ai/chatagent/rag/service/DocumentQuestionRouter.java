package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.rag.model.ConversationItemAnchor;
import org.javaup.ai.chatagent.rag.model.ConversationStructureAnchor;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationAction;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.model.RagRewriteResult;
import org.javaup.ai.chatagent.rag.model.RetrievalQuestionPlan;
import org.javaup.ai.manage.model.graph.GraphSection;
import org.javaup.ai.manage.service.DocumentNavigationIndexService;
import org.javaup.ai.manage.service.DocumentStructureGraphService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author: 阿星不是程序员
 **/

@Slf4j
@Service
public class DocumentQuestionRouter {

    private static final Pattern SECTION_CODE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)+)");
    private static final Pattern STEP_REFERENCE_PATTERN = Pattern.compile("第\\s*([0-9一二三四五六七八九十百]+)\\s*步");
    private static final Pattern ORDINAL_REFERENCE_PATTERN = Pattern.compile("第\\s*([0-9一二三四五六七八九十百]+)\\s*(条|点|项|个)");
    private static final Pattern QUOTED_TEXT_PATTERN = Pattern.compile("[“\"']([^”\"']{2,40})[”\"']");

    private static final List<String> ADJACENCY_HINTS = List.of(
        "上一节", "下一节", "前一节", "后一节", "上一个章节", "下一个章节", "属于哪个章节", "章节位置"
    );

    private static final List<String> OUTLINE_HINTS = List.of(
        "包含哪些章节", "都包含哪些章节", "有哪些章节", "有哪些小节", "包含哪些小节", "章节列表", "目录"
    );

    private static final List<String> ITEM_HINTS = List.of(
        "哪一步", "哪一项", "第几步", "第几项", "具体步骤", "步骤中的"
    );

    private static final List<String> ANALYTIC_HINTS = List.of(
        "关系", "关联", "为什么", "原因", "可能原因", "影响", "区别", "对比", "比较",
        "如何理解", "怎么理解", "说明了什么", "是否意味着", "是否说明", "分析", "解释"
    );

    private final DocumentStructureGraphService graphService;
    private final ObjectProvider<DocumentNavigationIndexService> navigationIndexServiceProvider;

    public DocumentQuestionRouter(DocumentStructureGraphService graphService,
                                  ObjectProvider<DocumentNavigationIndexService> navigationIndexServiceProvider) {
        this.graphService = graphService;
        this.navigationIndexServiceProvider = navigationIndexServiceProvider;
    }

    public DocumentNavigationDecision route(Long documentId,
                                            String originalQuestion,
                                            RagRewriteResult rewriteResult) {
        String rewrittenQuestion = firstNonBlank(
            rewriteResult == null ? "" : rewriteResult.getRewrittenQuestion(),
            originalQuestion
        );
        List<String> subQuestions = normalizeSubQuestions(rewriteResult, rewrittenQuestion);
        RetrievalQuestionPlan retrievalPlan = new RetrievalQuestionPlan(rewrittenQuestion, subQuestions);
        String routeText = (safeText(originalQuestion) + " " + rewrittenQuestion).trim();
        boolean analyticQuestion = looksAnalyticQuestion(routeText);

        if ((asksAdjacency(routeText) || asksOutline(routeText)) && !analyticQuestion && subQuestions.size() <= 1) {
            GraphSection section = resolveSection(documentId, originalQuestion, rewrittenQuestion);
            return buildDecision(
                ExecutionMode.GRAPH_ONLY,
                asksAdjacency(routeText) ? DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP : DocumentNavigationAction.CHILD_SECTION_DESCEND,
                section,
                null,
                retrievalPlan,
                "结构型问题直接走图查询"
            );
        }

        Integer itemIndex = resolveExplicitItemIndex(routeText);
        if ((itemIndex != null || asksItemLookup(routeText)) && !analyticQuestion) {
            GraphSection section = resolveSection(documentId, originalQuestion, rewrittenQuestion);
            return buildDecision(
                ExecutionMode.GRAPH_THEN_EVIDENCE,
                DocumentNavigationAction.ITEM_REFERENCE,
                section,
                itemIndex,
                retrievalPlan,
                "编号项或步骤型问题走图定位取证"
            );
        }

        GraphSection assistedSection = null;
        if (analyticQuestion || asksOutline(routeText) || itemIndex != null || mentionsStructure(routeText)) {
            assistedSection = resolveSection(documentId, originalQuestion, rewrittenQuestion);
        }
        return buildDecision(
            ExecutionMode.RETRIEVAL,
            itemIndex != null ? DocumentNavigationAction.ITEM_REFERENCE : DocumentNavigationAction.FRESH_TOPIC,
            assistedSection,
            itemIndex,
            retrievalPlan,
            assistedSection == null
                ? "普通文档问题走混合检索"
                : "结构线索仅作为软提示辅助混合检索"
        );
    }

    private DocumentNavigationDecision buildDecision(ExecutionMode mode,
                                                     DocumentNavigationAction action,
                                                     GraphSection section,
                                                     Integer itemIndex,
                                                     RetrievalQuestionPlan retrievalPlan,
                                                     String reason) {
        ConversationStructureAnchor structureAnchor = section == null
            ? ConversationStructureAnchor.builder().scopeMode(mode == ExecutionMode.RETRIEVAL ? "NONE" : "GRAPH_UNRESOLVED").build()
            : ConversationStructureAnchor.builder()
                .rootSectionCode(section.getNodeCode())
                .rootSectionTitle(section.getTitle())
                .targetSectionHint(section.displayTitle())
                .structureNodeId(section.getNodeId())
                .canonicalPath(section.getCanonicalPath())
                .scopeMode(mode == ExecutionMode.RETRIEVAL ? "SOFT" : "GRAPH")
                .build();
        ConversationItemAnchor itemAnchor = itemIndex == null
            ? null
            : ConversationItemAnchor.builder().itemIndex(itemIndex).build();
        List<String> queryHints = buildQueryHints(retrievalPlan, section, itemIndex);
        String summaryText = "mode=" + mode.name()
            + "; reason=" + reason
            + "; section=" + (section == null ? "" : section.displayTitle())
            + "; itemIndex=" + (itemIndex == null ? "" : itemIndex);
        log.info("文档问答路由完成: mode={}, action={}, section='{}', itemIndex={}, reason='{}'",
            mode,
            action,
            section == null ? "" : section.displayTitle(),
            itemIndex,
            reason);
        return DocumentNavigationDecision.builder()
            .navigationAction(action)
            .executionMode(mode)
            .structureAnchor(structureAnchor)
            .itemAnchor(itemAnchor)
            .retrievalPlan(retrievalPlan)
            .summaryText(summaryText)
            .queryContextHints(queryHints)
            .softSectionHints(section == null ? List.of() : List.of(section.displayTitle()))
            .build();
    }

    private GraphSection resolveSection(Long documentId, String originalQuestion, String rewrittenQuestion) {
        if (documentId == null) {
            return null;
        }
        GraphSection byCode = resolveBySectionCode(documentId, originalQuestion, rewrittenQuestion);
        if (byCode != null) {
            return byCode;
        }

        GraphSection indexedMatch = resolveByNavigationIndex(documentId, originalQuestion, rewrittenQuestion);
        if (indexedMatch != null) {
            return indexedMatch;
        }
        List<String> phrases = buildSectionPhrases(originalQuestion, rewrittenQuestion);
        GraphSection localMatch = resolveByLocalStructure(documentId, phrases);
        if (localMatch != null) {
            return localMatch;
        }
        return graphService.findBestSection(documentId, rewrittenQuestion, "");
    }

    private GraphSection resolveBySectionCode(Long documentId, String originalQuestion, String rewrittenQuestion) {
        Matcher matcher = SECTION_CODE_PATTERN.matcher((safeText(originalQuestion) + " " + safeText(rewrittenQuestion)).trim());
        while (matcher.find()) {
            GraphSection section = graphService.findSectionByCode(documentId, matcher.group(1));
            if (section != null) {
                return section;
            }
        }
        return null;
    }

    private GraphSection resolveByLocalStructure(Long documentId, List<String> phrases) {
        if (phrases.isEmpty()) {
            return null;
        }
        List<GraphSection> sections = graphService.listSections(documentId);
        if (sections == null || sections.isEmpty()) {
            return null;
        }
        return sections.stream()
            .map(section -> new SectionScore(section, scoreSection(section, phrases)))
            .filter(score -> score.score() >= 45D)
            .max(Comparator.comparingDouble(SectionScore::score))
            .map(SectionScore::section)
            .orElse(null);
    }

    private GraphSection resolveByNavigationIndex(Long documentId, String originalQuestion, String rewrittenQuestion) {
        DocumentNavigationIndexService navigationIndexService = navigationIndexServiceProvider.getIfAvailable();
        if (navigationIndexService == null) {
            return null;
        }
        String query = firstNonBlank(rewrittenQuestion, originalQuestion);
        List<DocumentNavigationIndexService.NavigationSectionHit> hits = navigationIndexService.searchSections(
            documentId,
            query,
            detectFacet(query),
            "",
            query,
            5
        );
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        return graphService.findSectionById(documentId, hits.get(0).nodeId());
    }

    private double scoreSection(GraphSection section, List<String> phrases) {
        String title = normalize(section.getTitle());
        String path = normalize(section.getSectionPath());
        String anchor = normalize(section.getAnchorText());
        String content = normalize(section.getContentText());
        double best = 0D;
        for (String phrase : phrases) {
            String normalized = normalize(phrase);
            if (normalized.length() < 2) {
                continue;
            }
            if (path.contains(normalized)) {
                best = Math.max(best, 100D + normalized.length());
            }
            if (title.contains(normalized)) {
                best = Math.max(best, 90D + normalized.length());
            }
            if (anchor.contains(normalized)) {
                best = Math.max(best, 80D + normalized.length());
            }
            if (content.contains(normalized)) {
                best = Math.max(best, 45D + Math.min(normalized.length(), 20));
            }
        }
        return best;
    }

    private List<String> buildSectionPhrases(String originalQuestion, String rewrittenQuestion) {
        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        addCleanPhrase(phrases, originalQuestion);
        addCleanPhrase(phrases, rewrittenQuestion);
        addQuotedPhrases(phrases, originalQuestion);
        addQuotedPhrases(phrases, rewrittenQuestion);
        for (String marker : ADJACENCY_HINTS) {
            addTextBefore(phrases, originalQuestion, marker);
            addTextBefore(phrases, rewrittenQuestion, marker);
        }
        for (String marker : OUTLINE_HINTS) {
            addTextBefore(phrases, originalQuestion, marker);
            addTextBefore(phrases, rewrittenQuestion, marker);
        }
        Matcher stepMatcher = STEP_REFERENCE_PATTERN.matcher(safeText(originalQuestion) + " " + safeText(rewrittenQuestion));
        while (stepMatcher.find()) {
            String all = stepMatcher.group();
            addTextBefore(phrases, originalQuestion, all);
            addTextBefore(phrases, rewrittenQuestion, all);
        }
        return new ArrayList<>(phrases).stream()
            .filter(item -> normalize(item).length() >= 2)
            .limit(8)
            .toList();
    }

    private void addTextBefore(LinkedHashSet<String> phrases, String text, String marker) {
        String normalized = safeText(text);
        if (normalized.isBlank() || marker == null || marker.isBlank()) {
            return;
        }
        int index = normalized.indexOf(marker);
        if (index > 0) {
            addCleanPhrase(phrases, normalized.substring(0, index));
        }
    }

    private void addQuotedPhrases(LinkedHashSet<String> phrases, String text) {
        Matcher matcher = QUOTED_TEXT_PATTERN.matcher(safeText(text));
        while (matcher.find()) {
            addCleanPhrase(phrases, matcher.group(1));
        }
    }

    private void addCleanPhrase(LinkedHashSet<String> phrases, String text) {
        String cleaned = cleanPhrase(text);
        if (StrUtil.isNotBlank(cleaned)) {
            phrases.add(cleaned);
        }
    }

    private String cleanPhrase(String text) {
        return safeText(text)
            .replace("刚才说的", "")
            .replace("请问", "")
            .replace("帮我", "")
            .replace("这个", "")
            .replace("那个", "")
            .replace("所属的具体章节", "")
            .replace("所属章节", "")
            .replace("具体章节", "")
            .replace("章节", "")
            .replace("小节", "")
            .replace("目录", "")
            .replace("上一节", "")
            .replace("下一节", "")
            .replace("分别是什么", "")
            .replace("是什么", "")
            .replace("有哪些", "")
            .replace("都有哪些", "")
            .replace("包含哪些", "")
            .replace("中的", "")
            .replace("里面的", "")
            .replace("里的", "")
            .replace("中", "")
            .replace("“", "")
            .replace("”", "")
            .replace("?", "")
            .replace("？", "")
            .trim();
    }

    private boolean asksAdjacency(String question) {
        return ADJACENCY_HINTS.stream().anyMatch(question::contains);
    }

    private boolean asksOutline(String question) {
        return OUTLINE_HINTS.stream().anyMatch(question::contains);
    }

    private boolean asksItemLookup(String question) {
        return ITEM_HINTS.stream().anyMatch(question::contains);
    }

    private boolean looksAnalyticQuestion(String question) {
        String normalized = safeText(question);
        return ANALYTIC_HINTS.stream().anyMatch(normalized::contains);
    }

    private boolean mentionsStructure(String question) {
        String normalized = safeText(question);
        return normalized.contains("章节")
            || normalized.contains("小节")
            || normalized.contains("条目")
            || normalized.contains("步骤")
            || normalized.contains("项")
            || QUOTED_TEXT_PATTERN.matcher(normalized).find()
            || SECTION_CODE_PATTERN.matcher(normalized).find();
    }

    private Integer resolveExplicitItemIndex(String question) {
        Matcher stepMatcher = STEP_REFERENCE_PATTERN.matcher(safeText(question));
        if (stepMatcher.find()) {
            return parseChineseNumber(stepMatcher.group(1));
        }
        Matcher ordinalMatcher = ORDINAL_REFERENCE_PATTERN.matcher(safeText(question));
        if (ordinalMatcher.find()) {
            return parseChineseNumber(ordinalMatcher.group(1));
        }
        return null;
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

    private List<String> normalizeSubQuestions(RagRewriteResult rewriteResult, String fallbackQuestion) {
        if (rewriteResult == null || rewriteResult.getSubQuestions() == null || rewriteResult.getSubQuestions().isEmpty()) {
            return List.of(fallbackQuestion);
        }
        return rewriteResult.getSubQuestions().stream()
            .filter(StrUtil::isNotBlank)
            .map(String::trim)
            .distinct()
            .toList();
    }

    private List<String> extractQueryHints(String question) {
        String normalized = safeText(question);
        if (normalized.isBlank()) {
            return List.of();
        }
        return Arrays.stream(normalized.split("[\\s、，,；;：:（）()\\-的和及与或]+"))
            .map(String::trim)
            .filter(item -> item.length() >= 2)
            .distinct()
            .limit(6)
            .toList();
    }

    private List<String> buildQueryHints(RetrievalQuestionPlan retrievalPlan,
                                         GraphSection section,
                                         Integer itemIndex) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (retrievalPlan != null) {
            hints.addAll(extractQueryHints(retrievalPlan.getRetrievalQuestion()));
        }
        if (section != null) {
            addHint(hints, section.displayTitle());
            addHint(hints, section.getTitle());
            addHint(hints, section.getNodeCode());
        }
        if (itemIndex != null) {
            addHint(hints, "第" + itemIndex + "步");
            addHint(hints, "第" + itemIndex + "项");
        }
        return hints.stream()
            .filter(StrUtil::isNotBlank)
            .limit(10)
            .toList();
    }

    private void addHint(LinkedHashSet<String> hints, String hint) {
        String normalized = safeText(hint);
        if (normalized.isBlank()) {
            return;
        }
        hints.add(normalized);
    }

    private String detectFacet(String question) {
        if (asksAdjacency(question)) {
            return "章节位置";
        }
        if (asksOutline(question)) {
            return "章节";
        }
        if (asksItemLookup(question)) {
            return "步骤";
        }
        return "";
    }

    private String normalize(String text) {
        return safeText(text).replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"']+", "").toLowerCase();
    }

    private String firstNonBlank(String left, String right) {
        if (StrUtil.isNotBlank(left)) {
            return left.trim();
        }
        return StrUtil.blankToDefault(right, "");
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    private record SectionScore(GraphSection section, double score) {
    }
}
