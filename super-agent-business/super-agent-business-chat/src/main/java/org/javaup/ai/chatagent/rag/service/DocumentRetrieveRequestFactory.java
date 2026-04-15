package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.rag.core.intent.SectionNodeScore;
import org.javaup.ai.chatagent.rag.core.intent.SubQuestionIntent;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
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
 * 检索请求构造器（重构版）。
 *
 * <p>与旧版的核心区别：<b>移除了所有 DocumentNavigationDecision 依赖</b>。
 * 不再有 strictSectionHints、strictCanonicalPathHints 等硬过滤条件。</p>
 *
 * <p>章节意图分类的结果只作为 queryContextHints 传入（boost），
 * 不作为 WHERE 过滤条件（filter）。这是新架构"软路由"的核心体现。</p>
 */
@Slf4j
@Component
public class DocumentRetrieveRequestFactory {

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(20\\d{2})\\b");
    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "(第\\s*[一二三四五六七八九十百0-9]+\\s*[章节条部分])|(附录\\s*[A-Za-z一二三四五六七八九十0-9]+)");

    private static final List<String> DOCUMENT_NAME_HINTS = List.of(
            "部署手册", "配置手册", "操作手册", "用户手册", "快速开始", "接入指南", "FAQ", "常见问题",
            "说明文档", "说明书", "规范", "指南", "手册", "文档");

    private static final List<String> BUSINESS_CATEGORY_HINTS = List.of(
            "流程", "规则", "操作手册", "部署", "配置", "接入", "协议", "故障", "排错", "规范", "说明");

    private static final List<String> DOCUMENT_TAG_HINTS = List.of(
            "2024", "2025", "2026", "部署", "配置", "接入", "协议", "FAQ", "故障", "排错", "升级", "兼容");

    /**
     * 构造统一检索请求。
     *
     * <p>章节意图分类结果只作为 queryContextHints 传入，
     * 用于关键词通道的辅助匹配，不作为硬过滤条件。</p>
     */
    public DocumentRetrieveRequest build(String subQuestion, ConversationExecutionPlan plan, int topK) {
        String normalizedQuestion = subQuestion == null ? "" : subQuestion.trim();

        // 构建查询增强：子问题本身已经是改写后的独立问题，直接使用
        List<String> contextHints = buildContextHints(normalizedQuestion, plan);
        String retrievalQuery = normalizedQuestion;

        // 从问题中提取轻量级过滤线索（年份、章节引用、文档类型词）
        DocumentRetrieveFilters filters = extractFilters(normalizedQuestion);

        DocumentRetrieveRequest request = new DocumentRetrieveRequest(
                normalizedQuestion,
                retrievalQuery,
                plan.getSelectedDocumentId(),
                plan.getSelectedTaskId(),
                topK,
                filters,
                contextHints
        );

        log.info("检索请求构造: question='{}', retrievalQuery='{}', documentId={}, taskId={}, contextHints={}",
                normalizedQuestion, request.getRetrievalQuery(),
                request.getDocumentId(), request.getTaskId(), contextHints);
        return request;
    }

    /**
     * 从章节意图分类结果中提取上下文提示词。
     *
     * <p>这些提示词只用于 boost（提高相关章节的匹配权重），
     * 不用于 filter（排除其他章节的结果）。</p>
     */
    private List<String> buildContextHints(String question, ConversationExecutionPlan plan) {
        List<String> hints = new ArrayList<>();

        // 从章节意图分类结果中提取高分章节的标题作为 hints
        if (plan.getSubQuestionIntents() != null) {
            for (SubQuestionIntent intent : plan.getSubQuestionIntents()) {
                if (intent.subQuestion().equals(question) || plan.getSubQuestionIntents().size() == 1) {
                    for (SectionNodeScore score : intent.sectionScores()) {
                        String title = score.node().getTitle();
                        if (StrUtil.isNotBlank(title)) {
                            hints.add(title);
                        }
                        // 最多取 3 个章节标题作为 hints
                        if (hints.size() >= 3) break;
                    }
                    break;
                }
            }
        }

        return hints;
    }

    /**
     * 从问题文本中提取轻量级过滤线索。
     *
     * <p>只提取确定性高的线索（年份、明确的章节引用、文档类型词），
     * 不再依赖导航决策的 strict 过滤条件。</p>
     */
    private DocumentRetrieveFilters extractFilters(String question) {
        if (StrUtil.isBlank(question)) {
            return DocumentRetrieveFilters.builder().build();
        }

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

        return DocumentRetrieveFilters.builder()
                .documentNameHints(new ArrayList<>(documentNameHints))
                .businessCategoryHints(new ArrayList<>(businessCategoryHints))
                .documentTagHints(new ArrayList<>(documentTagHints))
                .sectionPathHints(new ArrayList<>(sectionPathHints))
                .yearHints(new ArrayList<>(yearHints))
                .build();
    }
}
