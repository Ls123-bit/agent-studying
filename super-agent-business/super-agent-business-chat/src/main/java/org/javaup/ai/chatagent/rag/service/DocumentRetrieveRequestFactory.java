package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.javaup.ai.chatagent.rag.model.HistoryPlanningContext;
import org.javaup.ai.manage.model.DocumentRetrieveFilters;
import org.javaup.ai.manage.model.DocumentRetrieveRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索请求构造器。
 *
 * <p>负责把“子问题 + 历史线索”转换成统一的检索请求对象，
 * 避免各个通道自己散落实现一套过滤提示提取逻辑。</p>
 */
@Slf4j
@Component
public class DocumentRetrieveRequestFactory {

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(20\\d{2})\\b");
    private static final Pattern SECTION_PATTERN = Pattern.compile("(第\\s*[一二三四五六七八九十百0-9]+\\s*[章节条部分])|(附录\\s*[A-Za-z一二三四五六七八九十0-9]+)");
    private static final Pattern STEP_PATTERN = Pattern.compile("第\\s*[一二三四五六七八九十百0-9]+\\s*步");

    private static final List<String> DOCUMENT_NAME_HINTS = List.of(
        "部署手册", "配置手册", "操作手册", "用户手册", "快速开始", "接入指南", "FAQ", "常见问题",
        "说明文档", "说明书", "规范", "指南", "手册", "文档"
    );

    private static final List<String> BUSINESS_CATEGORY_HINTS = List.of(
        "流程", "规则", "操作手册", "部署", "配置", "接入", "协议", "故障", "排错", "规范", "说明"
    );

    private static final List<String> DOCUMENT_TAG_HINTS = List.of(
        "2024", "2025", "2026", "部署", "配置", "接入", "协议", "FAQ", "故障", "排错", "升级", "兼容"
    );

    /**
     * 构造统一检索请求。
     */
    public DocumentRetrieveRequest build(String subQuestion, ConversationExecutionPlan plan, int topK) {
        String normalizedQuestion = subQuestion == null ? "" : subQuestion.trim();
        /*
         * 这里把“问题改写后的子问题”和“历史里沉淀下来的检索提示”重新揉成一个请求对象，
         * 是为了让下游通道只处理一种统一输入：
         * 原问题 + retrievalQuery + document/task scope + metadata filters
         *
         * 这样 vector / keyword / ES 不需要各自重复实现一套
         * “从问题里抽年份、章节、手册类型”的逻辑。
         */
        QueryAugmentation augmentation = buildQueryAugmentation(
            normalizedQuestion,
            plan.getHistoryPlanningContext(),
            plan.getNavigationDecision()
        );
        DocumentRetrieveFilters filters = buildFilters(normalizedQuestion, plan.getNavigationDecision());
        DocumentRetrieveRequest request = new DocumentRetrieveRequest(
            normalizedQuestion,
            augmentation.retrievalQuery(),
            plan.getSelectedDocumentId(),
            plan.getSelectedTaskId(),
            topK,
            filters,
            augmentation.queryContextHints()
        );
        log.info("检索请求构造: originalSubQuestion='{}', retrievalQuery='{}', documentId={}, taskId={}, anchorApplied={}, rootTopic='{}', targetFacet='{}', targetSectionHint='{}', strictSectionHints={}, strictCanonicalPathHints={}, strictStructureNodeIds={}, strictItemIndexes={}, softSectionHints={}, queryContextHints={}",
            normalizedQuestion,
            request.getRetrievalQuery(),
            request.getDocumentId(),
            request.getTaskId(),
            plan.getNavigationDecision() != null && plan.getNavigationDecision().isAnchorApplied(),
            plan.getNavigationDecision() == null || plan.getNavigationDecision().getSubjectAnchor() == null ? "" : StrUtil.blankToDefault(plan.getNavigationDecision().getSubjectAnchor().getAnchorText(), ""),
            plan.getNavigationDecision() == null || plan.getNavigationDecision().getTopicAnchor() == null ? "" : StrUtil.blankToDefault(plan.getNavigationDecision().getTopicAnchor().getFacet(), ""),
            plan.getNavigationDecision() == null || plan.getNavigationDecision().getStructureAnchor() == null ? "" : StrUtil.blankToDefault(plan.getNavigationDecision().getStructureAnchor().getTargetSectionHint(), ""),
            filters == null ? List.of() : filters.getSectionPathHints(),
            filters == null ? List.of() : filters.getCanonicalPathHints(),
            filters == null ? List.of() : filters.getStructureNodeIdHints(),
            filters == null ? List.of() : filters.getItemIndexHints(),
            plan.getNavigationDecision() == null ? List.of() : plan.getNavigationDecision().getSoftSectionHints(),
            request.getQueryContextHints());
        return request;
    }

    /**
     * 为检索请求生成“增强后的查询文本”和“额外提示词”。
     *
     * <p>这里刻意不直接改写用户原问题，而是把检索增强单独建模成 retrievalQuery。
     * 这样学员在阅读代码时能一眼看懂：
     * - 用户说了什么
     * - 我们拿什么去检索
     * - 哪些提示词只是轻量 boost，不应直接覆盖原问题</p>
     */
    private QueryAugmentation buildQueryAugmentation(String normalizedQuestion,
                                                     HistoryPlanningContext historyPlanningContext,
                                                     DocumentNavigationDecision navigationDecision) {
        if (StrUtil.isBlank(normalizedQuestion)) {
            return new QueryAugmentation("", List.of());
        }
        if (navigationDecision != null
            && (navigationDecision.isAnchorApplied() || navigationDecision.isMissingRequestedStructure())) {
            return new QueryAugmentation(
                normalizedQuestion,
                mergeHints(navigationDecision.getQueryContextHints(), List.of())
            );
        }
        boolean shortFollowUp = looksLikeShortFollowUp(normalizedQuestion);
        if (!shortFollowUp
            || historyPlanningContext == null
            || historyPlanningContext.getQueryContextHints() == null
            || historyPlanningContext.getQueryContextHints().isEmpty()) {
            return new QueryAugmentation(normalizedQuestion, List.of());
        }
        /*
         * 历史提示词只在“明显像短追问”的场景参与检索增强。
         * 这样可以避免把一段已经很完整的问题，再额外污染成一大坨历史关键词。
         */
        List<String> normalizedHints = historyPlanningContext.getQueryContextHints().stream()
            .filter(StrUtil::isNotBlank)
            .map(String::trim)
            .distinct()
            .limit(4)
            .toList();
        if (normalizedHints.isEmpty()) {
            return new QueryAugmentation(normalizedQuestion, List.of());
        }
        /*
         * retrievalQuery 只面向检索层消费，因此这里不再加“检索提示：”这类说明性标签，
         * 而是直接把短追问和少量上下文关键词拼成一条更纯粹的检索查询。
         *
         * 这么做的目的很明确：
         * 1. 向量检索真正吃到补全后的查询语义
         * 2. 关键词检索的主 query 也能命中这些上下文关键词
         * 3. 不把说明性文本本身引入 embedding 噪音
         */
        String retrievalQuery = (normalizedQuestion + " " + String.join(" ", normalizedHints)).trim();
        return new QueryAugmentation(retrievalQuery, normalizedHints);
    }

    private DocumentRetrieveFilters buildFilters(String question, DocumentNavigationDecision navigationDecision) {
        if (navigationDecision != null
            && (navigationDecision.isAnchorApplied() || navigationDecision.isMissingRequestedStructure())) {
            return mergeAnchorStructureHints(
                mergeAnchorSectionHints(DocumentRetrieveFilters.builder().build(), navigationDecision),
                navigationDecision
            );
        }
        if (StrUtil.isBlank(question)) {
            return mergeAnchorStructureHints(
                mergeAnchorSectionHints(DocumentRetrieveFilters.builder().build(), navigationDecision),
                navigationDecision
            );
        }
        /*
         * filters 的设计目标不是“把所有可能字段都猜出来”，
         * 而是优先抽那些一旦命中就对检索精度很有帮助的强线索：
         * - 年份
         * - 章节/附录
         * - 手册/部署/FAQ 这类文档类型词
         * - 历史里已经沉淀下来的 retrieval hints
         *
         * 最后把它们按字段分类打包，交给底层检索服务分别决定：
         * 是做硬过滤，还是做软加权。
         */
        String normalized = question.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> documentNameHints = new LinkedHashSet<>();
        LinkedHashSet<String> businessCategoryHints = new LinkedHashSet<>();
        LinkedHashSet<String> documentTagHints = new LinkedHashSet<>();
        LinkedHashSet<String> sectionPathHints = new LinkedHashSet<>();
        LinkedHashSet<String> yearHints = new LinkedHashSet<>();

        Matcher yearMatcher = YEAR_PATTERN.matcher(question);
        while (yearMatcher.find()) {
            yearHints.add(yearMatcher.group(1));
        }

        Matcher sectionMatcher = SECTION_PATTERN.matcher(question);
        while (sectionMatcher.find()) {
            if (StrUtil.isNotBlank(sectionMatcher.group())) {
                sectionPathHints.add(sectionMatcher.group().replaceAll("\\s+", ""));
            }
        }

        for (String hint : DOCUMENT_NAME_HINTS) {
            if (normalized.contains(hint.toLowerCase(Locale.ROOT))) {
                documentNameHints.add(hint);
            }
        }
        for (String hint : BUSINESS_CATEGORY_HINTS) {
            if (normalized.contains(hint.toLowerCase(Locale.ROOT))) {
                businessCategoryHints.add(hint);
            }
        }
        for (String hint : DOCUMENT_TAG_HINTS) {
            if (normalized.contains(hint.toLowerCase(Locale.ROOT))) {
                documentTagHints.add(hint);
            }
        }

        return mergeAnchorStructureHints(
            mergeAnchorSectionHints(DocumentRetrieveFilters.builder()
                .documentNameHints(new ArrayList<>(documentNameHints))
                .businessCategoryHints(new ArrayList<>(businessCategoryHints))
                .documentTagHints(new ArrayList<>(documentTagHints))
                .sectionPathHints(new ArrayList<>(sectionPathHints))
                .yearHints(new ArrayList<>(yearHints))
                .build(), navigationDecision),
            navigationDecision
        );
    }

    private DocumentRetrieveFilters mergeAnchorSectionHints(DocumentRetrieveFilters filters,
                                                            DocumentNavigationDecision navigationDecision) {
        if (navigationDecision == null
            || navigationDecision.getStrictSectionHints() == null
            || navigationDecision.getStrictSectionHints().isEmpty()) {
            return filters;
        }
        LinkedHashSet<String> mergedSectionHints = new LinkedHashSet<>();
        if (filters != null && filters.getSectionPathHints() != null) {
            mergedSectionHints.addAll(filters.getSectionPathHints());
        }
        mergedSectionHints.addAll(navigationDecision.getStrictSectionHints().stream()
            .filter(StrUtil::isNotBlank)
            .map(String::trim)
            .toList());
        DocumentRetrieveFilters workingFilters = filters == null ? DocumentRetrieveFilters.builder().build() : filters;
        workingFilters.setSectionPathHints(new ArrayList<>(mergedSectionHints));
        return workingFilters;
    }

    private DocumentRetrieveFilters mergeAnchorStructureHints(DocumentRetrieveFilters filters,
                                                              DocumentNavigationDecision navigationDecision) {
        if (navigationDecision == null) {
            return filters;
        }
        DocumentRetrieveFilters workingFilters = filters == null ? DocumentRetrieveFilters.builder().build() : filters;
        workingFilters.setCanonicalPathHints(mergeHints(
            workingFilters.getCanonicalPathHints(),
            navigationDecision.getStrictCanonicalPathHints()
        ));
        workingFilters.setStructureNodeIdHints(mergeLongHints(
            workingFilters.getStructureNodeIdHints(),
            navigationDecision.getStrictStructureNodeIds()
        ));
        workingFilters.setItemIndexHints(mergeIntegerHints(
            workingFilters.getItemIndexHints(),
            navigationDecision.getStrictItemIndexes()
        ));
        return workingFilters;
    }

    private List<String> mergeHints(List<String> primaryHints, List<String> fallbackHints) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (primaryHints != null) {
            primaryHints.stream().filter(StrUtil::isNotBlank).map(String::trim).forEach(merged::add);
        }
        if (fallbackHints != null) {
            fallbackHints.stream().filter(StrUtil::isNotBlank).map(String::trim).forEach(merged::add);
        }
        return new ArrayList<>(merged);
    }

    private List<Long> mergeLongHints(List<Long> primaryHints, List<Long> fallbackHints) {
        LinkedHashSet<Long> merged = new LinkedHashSet<>();
        if (primaryHints != null) {
            primaryHints.stream().filter(java.util.Objects::nonNull).forEach(merged::add);
        }
        if (fallbackHints != null) {
            fallbackHints.stream().filter(java.util.Objects::nonNull).forEach(merged::add);
        }
        return new ArrayList<>(merged);
    }

    private List<Integer> mergeIntegerHints(List<Integer> primaryHints, List<Integer> fallbackHints) {
        LinkedHashSet<Integer> merged = new LinkedHashSet<>();
        if (primaryHints != null) {
            primaryHints.stream().filter(java.util.Objects::nonNull).forEach(merged::add);
        }
        if (fallbackHints != null) {
            fallbackHints.stream().filter(java.util.Objects::nonNull).forEach(merged::add);
        }
        return new ArrayList<>(merged);
    }

    private boolean looksLikeShortFollowUp(String question) {
        if (StrUtil.isBlank(question)) {
            return false;
        }
        String normalized = question.trim();
        return normalized.length() <= 16
            || normalized.contains("这个")
            || normalized.contains("那个")
            || normalized.contains("上面")
            || normalized.contains("前面")
            || normalized.contains("刚才")
            || STEP_PATTERN.matcher(normalized).find();
    }

    private record QueryAugmentation(
        String retrievalQuery,
        List<String> queryContextHints
    ) {
    }
}
