package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
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
@Component
public class DocumentRetrieveRequestFactory {

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(20\\d{2})\\b");
    private static final Pattern PAGE_PATTERN = Pattern.compile("(?:(?:第)?\\s*(\\d{1,4})\\s*页)|(?:\\bp\\s*(\\d{1,4})\\b)", Pattern.CASE_INSENSITIVE);
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
     * 构造统一检索请求。
     */
    public DocumentRetrieveRequest build(String subQuestion, ConversationExecutionPlan plan, int topK) {
        /*
         * 这里把“问题改写后的子问题”和“历史里沉淀下来的检索提示”重新揉成一个请求对象，
         * 是为了让下游通道只处理一种统一输入：
         * question + document/task scope + metadata filters
         *
         * 这样 vector / keyword / ES 不需要各自重复实现一套
         * “从问题里抽年份、页码、章节、手册类型”的逻辑。
         */
        String effectiveQuestion = buildEffectiveQuestion(subQuestion, plan.getHistoryPlanningContext());
        return new DocumentRetrieveRequest(
            subQuestion == null ? "" : subQuestion.trim(),
            plan.getSelectedDocumentIds(),
            plan.getSelectedTaskIds(),
            topK,
            buildFilters(subQuestion, plan.getHistoryPlanningContext()),
            buildQueryContextHints(effectiveQuestion, plan.getHistoryPlanningContext())
        );
    }

    private String buildEffectiveQuestion(String subQuestion, HistoryPlanningContext historyPlanningContext) {
        if (StrUtil.isBlank(subQuestion)) {
            return "";
        }
        String normalizedQuestion = subQuestion.trim();
        if (historyPlanningContext == null || historyPlanningContext.getRetrievalHints().isEmpty()) {
            return normalizedQuestion;
        }
        if (!looksLikeShortFollowUp(normalizedQuestion)) {
            return normalizedQuestion;
        }
        /*
         * 这里不对所有问题都盲目补 retrieval hints，
         * 只在“明显像短追问/指代追问”的场景补。
         *
         * 否则对于已经很完整的问题，再额外拼一段历史关键词，
         * 反而可能把检索 query 污染得更重。
         */
        String hintText = historyPlanningContext.getRetrievalHints().stream()
            .filter(StrUtil::isNotBlank)
            .limit(3)
            .reduce((left, right) -> left + "；" + right)
            .orElse("");
        if (StrUtil.isBlank(hintText)) {
            return normalizedQuestion;
        }
        return normalizedQuestion + "\n检索提示：" + hintText;
    }

    private DocumentRetrieveFilters buildFilters(String question, HistoryPlanningContext historyPlanningContext) {
        if (StrUtil.isBlank(question)) {
            return DocumentRetrieveFilters.builder().build();
        }
        /*
         * filters 的设计目标不是“把所有可能字段都猜出来”，
         * 而是优先抽那些一旦命中就对检索精度很有帮助的强线索：
         * - 年份
         * - 页码
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
        LinkedHashSet<String> pageHints = new LinkedHashSet<>();
        LinkedHashSet<String> yearHints = new LinkedHashSet<>();

        Matcher yearMatcher = YEAR_PATTERN.matcher(question);
        while (yearMatcher.find()) {
            yearHints.add(yearMatcher.group(1));
        }

        Matcher pageMatcher = PAGE_PATTERN.matcher(question);
        while (pageMatcher.find()) {
            String page = pageMatcher.group(1) != null ? pageMatcher.group(1) : pageMatcher.group(2);
            if (StrUtil.isNotBlank(page)) {
                pageHints.add(page.trim());
            }
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

        return DocumentRetrieveFilters.builder()
            .documentNameHints(new ArrayList<>(documentNameHints))
            .businessCategoryHints(new ArrayList<>(businessCategoryHints))
            .documentTagHints(new ArrayList<>(documentTagHints))
            .sectionPathHints(new ArrayList<>(sectionPathHints))
            .pageHints(new ArrayList<>(pageHints))
            .yearHints(new ArrayList<>(yearHints))
            .build();
    }

    private List<String> buildQueryContextHints(String effectiveQuestion, HistoryPlanningContext historyPlanningContext) {
        if (StrUtil.isBlank(effectiveQuestion)
            || historyPlanningContext == null
            || historyPlanningContext.getQueryContextHints() == null
            || historyPlanningContext.getQueryContextHints().isEmpty()) {
            return List.of();
        }
        if (!looksLikeShortFollowUp(effectiveQuestion)) {
            return List.of();
        }
        return historyPlanningContext.getQueryContextHints().stream()
            .filter(StrUtil::isNotBlank)
            .map(String::trim)
            .distinct()
            .limit(4)
            .toList();
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
            || normalized.contains("刚才");
    }
}
