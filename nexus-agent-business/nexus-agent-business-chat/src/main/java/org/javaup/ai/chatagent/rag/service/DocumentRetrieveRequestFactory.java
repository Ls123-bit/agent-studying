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


@Slf4j
@Component
public class DocumentRetrieveRequestFactory {

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(20\\d{2})\\b");
    private static final Pattern SECTION_PATTERN = Pattern.compile("(第\\s*[一二三四五六七八九十百0-9]+\\s*[章节条部分])|(附录\\s*[A-Za-z一二三四五六七八九十0-9]+)");

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
     * 把子问题、历史上下文和路由锚点转换成统一的文档检索请求。
     *
     * <p>这个工厂方法是向量通道和关键词通道共用的前置步骤：它会同时构造
     * retrievalQuery、filters、queryContextHints、documentIds 和 taskIds，
     * 让下游检索只在正确的知识范围内工作。</p>
     * ————————————————————————————————————————————————————————————————————
     * 整个方法做了三件事：
     * 1.Query 增强：调用 buildQueryAugmentation，把原始子问题改写成更适合检索的 retrievalQuery，同时生成 queryContextHints
     * 2.过滤条件提取：调用 buildFilters，从问题文本中抽取年份、章节、文档名等结构化过滤条件
     * 3.范围限定：根据执行计划中的 documentIds 和 taskIds，把检索范围锁定在正确的知识库范围内
     */
    public DocumentRetrieveRequest build(String subQuestion, ConversationExecutionPlan plan, int topK) {
        // 先把子问题归一化，去掉首尾空白，后续所有处理都围绕这个标准化文本进行。
        String normalizedQuestion = StrUtil.blankToDefault(subQuestion, "").trim();
        // 组装检索 query 的增强信息：历史上下文提示、导航提示和关键词提示。
        QueryAugmentation augmentation = buildQueryAugmentation(
            normalizedQuestion,
            plan.getHistoryPlanningContext(),
            plan.getNavigationDecision()
        );
        // 根据问题文本抽取过滤提示，例如年份、章节名、文档名、业务分类等。
        DocumentRetrieveFilters filters = buildFilters(normalizedQuestion);
        // 统一构造 DocumentRetrieveRequest，后续向量和关键词检索都直接消费这个对象。
        DocumentRetrieveRequest request = new DocumentRetrieveRequest(
            normalizedQuestion,
            augmentation.retrievalQuery(),
            plan.getSelectedDocumentId(),
            plan.getSelectedTaskId(),
            topK,
            filters,
            augmentation.queryContextHints()
        );
        // 如果上游已经给出了更具体的 documentIds，则沿用它；否则退回到单一 selectedDocumentId。
        request.setDocumentIds(plan.getRetrievalDocumentIds() == null || plan.getRetrievalDocumentIds().isEmpty()
            ? (plan.getSelectedDocumentId() == null ? List.of() : List.of(plan.getSelectedDocumentId()))
            : plan.getRetrievalDocumentIds());
        // taskIds 的策略与 documentIds 一致，优先使用上游显式限定的范围。
        request.setTaskIds(plan.getRetrievalTaskIds() == null || plan.getRetrievalTaskIds().isEmpty()
            ? (plan.getSelectedTaskId() == null ? List.of() : List.of(plan.getSelectedTaskId()))
            : plan.getRetrievalTaskIds());
        // 记录一次检索请求构造结果，便于排查问题是否被正确改写和过滤。
        log.info("检索请求构造: originalSubQuestion='{}', retrievalQuery='{}', documentId={}, taskId={}, sectionHints={}, yearHints={}, queryContextHints={}",
            normalizedQuestion,
            request.getRetrievalQuery(),
            request.getDocumentId(),
            request.getTaskId(),
            filters == null ? List.of() : filters.getSectionPathHints(),
            filters == null ? List.of() : filters.getYearHints(),
            request.getQueryContextHints());
        return request;
    }



    /*这个方法的核心目标是：让检索 query 比原始问题更"丰满"，尤其是当用户问的是短追问时。

     */
    private QueryAugmentation buildQueryAugmentation(String normalizedQuestion,
                                                     HistoryPlanningContext historyPlanningContext,
                                                     DocumentNavigationDecision navigationDecision) {
        // 问题为空时直接返回空结果，不做任何增强。
        if (StrUtil.isBlank(normalizedQuestion)) {
            return new QueryAugmentation("", List.of());
        }
        // 从导航决策中提取导航提示，最多保留 4 个，去重去空。
        List<String> navigationHints = navigationDecision == null || navigationDecision.getQueryContextHints() == null
            ? List.of()
            : navigationDecision.getQueryContextHints().stream()
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .limit(4)
                .toList();

        /*
            进入这个分支有两种情况：
            情况1：问题本身信息量足够（不是短追问）
            这时候问题已经包含了足够的主题信息，不需要补充历史上下文。但如果路由器给出了导航提示（navigationHints），还是会把它拼到问题后面，进一步扩展检索范围。
            举个例子：
            用户问："Redis 的持久化方式有哪些？"（不是短追问）
            路由器导航提示：["Redis", "RDB", "AOF"]
            最终 retrievalQuery = "Redis 的持久化方式有哪些？ Redis RDB AOF"
            情况2：是短追问，但没有历史上下文
            比如第一轮对话就直接问"它是什么？"，这时候没有历史上下文可以补充，只能用原始问题去检索，效果可能不太好，但也没有更好的办法。
            两种情况的处理逻辑一样：
            没有导航提示 → retrievalQuery = normalizedQuestion，queryContextHints 用 extractMeaningfulTerms 从问题中提取
            有导航提示 → retrievalQuery = normalizedQuestion + " " + navigationHints，queryContextHints = 导航提示 + 问题关键词
         */
        // 如果不是短追问，或者没有历史上下文提示，就走"非追问"分支。
        if (!looksLikeShortFollowUp(normalizedQuestion)
            || historyPlanningContext == null
            || historyPlanningContext.getQueryContextHints() == null
            || historyPlanningContext.getQueryContextHints().isEmpty()) {
            // 没有导航提示时，直接用原始问题作为 retrievalQuery。
            if (navigationHints.isEmpty()) {
                return new QueryAugmentation(normalizedQuestion, extractMeaningfulTerms(normalizedQuestion));
            }
            // 有导航提示时，把导航提示拼到问题后面，扩展检索范围。
            String retrievalQuery = (normalizedQuestion + " " + String.join(" ", navigationHints)).trim();
            List<String> queryHints = new ArrayList<>(navigationHints);
            queryHints.addAll(extractMeaningfulTerms(normalizedQuestion));
            return new QueryAugmentation(retrievalQuery, queryHints.stream().distinct().limit(8).toList());
        }





        /*
        分支二：短追问 + 有历史上下文
        这是最关键的分支——短追问 + 有历史上下文。处理步骤是：
        1.从 historyPlanningContext 取出历史关键词，去空去重，最多保留 4 个
        2.把历史关键词和导航提示合并成 allHints
        3.把 allHints 拼到原始问题后面，形成增强后的 retrievalQuery
        4.queryContextHints = allHints + 从问题中提取的关键词，去重后最多保留 8 个
         */
        // 走到这里说明是短追问，且有历史上下文提示，需要把历史关键词补充进来。
        List<String> normalizedHints = historyPlanningContext.getQueryContextHints().stream()
            .filter(StrUtil::isNotBlank)
            .map(String::trim)
            .distinct()
            .limit(4)// 最多取 4 个历史提示，避免 query 过长
            .toList();
        // 合并历史提示和导航提示。
        List<String> allHints = new ArrayList<>(normalizedHints);
        allHints.addAll(navigationHints);
        if (allHints.isEmpty()) {
            return new QueryAugmentation(normalizedQuestion, extractMeaningfulTerms(normalizedQuestion));
        }
        // 把所有提示词拼到问题后面，形成增强后的 retrievalQuery。
        String retrievalQuery = (normalizedQuestion + " " + String.join(" ", allHints)).trim();
        List<String> queryContextHints = new ArrayList<>(allHints);
        queryContextHints.addAll(extractMeaningfulTerms(normalizedQuestion));
        return new QueryAugmentation(retrievalQuery, queryContextHints.stream().distinct().limit(8).toList());
    }


    /*
    这个方法从问题文本中提取结构化的过滤条件，用于缩小检索范围。
     */
    private DocumentRetrieveFilters buildFilters(String question) {
        if (StrUtil.isBlank(question)) {
            return DocumentRetrieveFilters.builder().build();
        }
        String normalized = question.toLowerCase(Locale.ROOT);
        // 用 LinkedHashSet 保证去重且保留插入顺序。
        LinkedHashSet<String> documentNameHints = new LinkedHashSet<>();
        LinkedHashSet<String> businessCategoryHints = new LinkedHashSet<>();
        LinkedHashSet<String> documentTagHints = new LinkedHashSet<>();
        LinkedHashSet<String> sectionPathHints = new LinkedHashSet<>();
        LinkedHashSet<String> yearHints = new LinkedHashSet<>();

        // 用正则提取年份，比如 "2024年" 或 "2024"。
        Matcher yearMatcher = YEAR_PATTERN.matcher(question);
        while (yearMatcher.find()) {
            yearHints.add(yearMatcher.group(1));
        }

        // 用正则提取章节路径，比如 "第3章" "第2节" "3.1.2"。
        Matcher sectionMatcher = SECTION_PATTERN.matcher(question);
        while (sectionMatcher.find()) {
            if (StrUtil.isNotBlank(sectionMatcher.group())) {
                // 去掉空白，统一格式。
                sectionPathHints.add(sectionMatcher.group().replaceAll("\\s+", ""));
            }
        }

        // 遍历预定义的文档名关键词列表，检查问题中是否包含。
        for (String hint : DOCUMENT_NAME_HINTS) {
            if (normalized.contains(hint.toLowerCase(Locale.ROOT))) {
                documentNameHints.add(hint);
            }
        }
        // 遍历预定义的业务分类关键词列表。
        for (String hint : BUSINESS_CATEGORY_HINTS) {
            if (normalized.contains(hint.toLowerCase(Locale.ROOT))) {
                businessCategoryHints.add(hint);
            }
        }
        // 遍历预定义的文档标签关键词列表。
        for (String hint : DOCUMENT_TAG_HINTS) {
            if (normalized.contains(hint.toLowerCase(Locale.ROOT))) {
                documentTagHints.add(hint);
            }
        }

        return DocumentRetrieveFilters.builder()
            .documentNameHints(new ArrayList<>(documentNameHints))
            .businessCategoryHints(new ArrayList<>(businessCategoryHints))
            .documentTagHints(new ArrayList<>(documentTagHints))
            .sectionPathHints(new ArrayList<>(sectionPathHints))
            .yearHints(new ArrayList<>(yearHints))
            .build();
    }


    /*
    短追问判断
    判断逻辑很直接：
    长度 < 12：比如"它是什么？"（6个字）、"有什么用？"（6个字）
    包含指代词：它、这个、那个、刚才、前面、上面
    这些情况下，问题本身缺乏足够的上下文，单独拿去检索效果会很差。
    为什么是 12 个字符？
    这是一个经验值。中文问题如果少于 12 个字，通常信息量不足，比如"它怎么用？"（6字）、"有什么区别？"（7字）。超过 12 个字的问题一般已经包含了足够的主题信息，不需要额外补充上下文。
     */
    private boolean looksLikeShortFollowUp(String question) {
        if (StrUtil.isBlank(question)) {
            return false;
        }
        // 问题长度小于 12 个字符，或者包含指代词，就认为是短追问。
        return question.length() < 12
            || question.contains("它")
            || question.contains("这个")
            || question.contains("那个")
            || question.contains("刚才")
            || question.contains("前面")
            || question.contains("上面");
    }




    /*无论是否有导航提示，queryContextHints 都会调用 extractMeaningfulTerms 从问题中提取关键词
    这个方法从问题中提取有意义的词组，用于补充 queryContextHints。

     */
    private List<String> extractMeaningfulTerms(String question) {
        // 空问题直接返回空列表。
        if (StrUtil.isBlank(question)) {
            return List.of();
        }
        // LinkedHashSet 保证去重且保留插入顺序。
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        // 按常见分隔符切分问题，提取长度 >= 2 的片段。   分隔符包括：空白、顿号、逗号、分号、冒号、括号、连字符，以及"的""和""及""与""或"这些连接词。
        for (String segment : question.split("[\\s、，,；;：:（）()\\-的和及与或]+")) {
            String trimmed = segment.trim();
            if (trimmed.length() >= 2) {
                terms.add(trimmed);
            }
        }
        // 最多保留 6 个词组，避免 hints 过多影响检索精度。
        return new ArrayList<>(terms).stream().limit(6).toList();
    }

    private record QueryAugmentation(String retrievalQuery, List<String> queryContextHints) {
    }
}
