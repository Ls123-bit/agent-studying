package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.AnswerHistoryContext;
import org.javaup.ai.chatagent.rag.model.HistoryPlanningContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 回答阶段历史上下文装配器。
 *
 * <p>它专门解决一个教学上很重要的问题：</p>
 * <p>1. 记忆服务已经产出了长期摘要和最近对话原材料。</p>
 * <p>2. 回答阶段真正需要的却不是“原材料原样拼接”，而是一份带明确预算与优先级的上下文。</p>
 *
 * <p>当前装配规则强调两点：</p>
 * <p>1. 最近相关对话优先于较旧的结构化历史。</p>
 * <p>2. 承接式追问会给最近对话分配更高预算。</p>
 */
@Service
public class AnswerHistoryContextAssembler {

    private static final Set<String> FOLLOW_UP_HINTS = Set.of(
        "刚才", "上面", "前面", "前文", "上一条", "上一个", "上一轮", "这个", "那个", "这条", "那条",
        "继续", "展开", "补充", "详细", "细说", "进一步", "为什么", "怎么做", "怎么理解", "还有呢"
    );

    private final ChatRagProperties properties;

    public AnswerHistoryContextAssembler(ChatRagProperties properties) {
        this.properties = properties;
    }

    /**
     * 组装回答阶段最终使用的历史上下文。
     */
    public AnswerHistoryContext assemble(String question,
                                         HistoryPlanningContext historyPlanningContext,
                                         String answerRecentTranscript,
                                         String historySummary) {
        String normalizedQuestion = safeText(question);
        String structuredSource = buildStructuredContext(historyPlanningContext);
        String recentSource = normalizeRecentTranscript(answerRecentTranscript);
        String summaryFallback = buildSummaryFallback(historySummary);

        int totalBudget = Math.max(1, properties.getAnswerHistoryMaxChars());
        boolean hasStructured = StrUtil.isNotBlank(structuredSource) || StrUtil.isNotBlank(summaryFallback);
        boolean hasRecent = StrUtil.isNotBlank(recentSource);
        boolean followUpQuestion = looksLikeFollowUpQuestion(normalizedQuestion, hasRecent);

        if (!hasStructured && !hasRecent) {
            return AnswerHistoryContext.builder()
                .renderedText("")
                .structuredContext("")
                .recentContext("")
                .followUpQuestion(followUpQuestion)
                .totalBudget(totalBudget)
                .recentBudget(0)
                .structuredBudget(0)
                .build();
        }

        int recentBudget = resolveRecentBudget(totalBudget, hasStructured, hasRecent, followUpQuestion);
        String recentPart = renderRecentContext(recentSource, recentBudget);
        int structuredBudget = resolveStructuredBudget(totalBudget, recentPart);
        String structuredPart = renderStructuredContext(structuredSource, summaryFallback, structuredBudget);
        String renderedText = joinNonBlank(structuredPart, recentPart);

        return AnswerHistoryContext.builder()
            .renderedText(renderedText)
            .structuredContext(structuredPart)
            .recentContext(recentPart)
            .followUpQuestion(followUpQuestion)
            .totalBudget(totalBudget)
            .recentBudget(recentBudget)
            .structuredBudget(structuredBudget)
            .build();
    }

    private String buildStructuredContext(HistoryPlanningContext historyPlanningContext) {
        if (historyPlanningContext == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendSection(builder, "相关会话目标", historyPlanningContext.getConversationGoal());
        appendBulletSection(builder, "已确认事实", historyPlanningContext.getStableFacts(), 5);
        appendBulletSection(builder, "待跟进问题", historyPlanningContext.getPendingQuestions(), 4);
        return builder.toString().trim();
    }

    private String buildSummaryFallback(String historySummary) {
        String normalized = safeText(historySummary);
        if (normalized.isBlank()) {
            return "";
        }
        return "相关历史上下文：\n" + normalized;
    }

    private String normalizeRecentTranscript(String answerRecentTranscript) {
        String normalized = safeText(answerRecentTranscript);
        if (normalized.startsWith("【最近相关对话】")) {
            return normalized.substring("【最近相关对话】".length()).trim();
        }
        if (normalized.startsWith("最近相关对话：")) {
            return normalized.substring("最近相关对话：".length()).trim();
        }
        return normalized;
    }

    private boolean looksLikeFollowUpQuestion(String normalizedQuestion, boolean hasRecentContext) {
        if (!hasRecentContext || StrUtil.isBlank(normalizedQuestion)) {
            return false;
        }
        if (FOLLOW_UP_HINTS.stream().anyMatch(normalizedQuestion::contains)) {
            return true;
        }
        if (normalizedQuestion.matches(".*第\\s*[0-9一二三四五六七八九十百]+\\s*(条|点|项).*")) {
            return true;
        }
        if (normalizedQuestion.length() <= 12) {
            return true;
        }
        return normalizedQuestion.length() <= 18 && (normalizedQuestion.endsWith("呢") || normalizedQuestion.endsWith("吗"));
    }

    private int resolveRecentBudget(int totalBudget,
                                    boolean hasStructured,
                                    boolean hasRecent,
                                    boolean followUpQuestion) {
        if (!hasRecent) {
            return 0;
        }
        if (!hasStructured) {
            return totalBudget;
        }
        double recentRatio = followUpQuestion ? 0.72D : 0.45D;
        return Math.min(totalBudget, Math.max(0, (int) Math.round(totalBudget * recentRatio)));
    }

    private int resolveStructuredBudget(int totalBudget, String recentPart) {
        if (totalBudget <= 0) {
            return 0;
        }
        int separatorCost = recentPart == null || recentPart.isBlank() ? 0 : 2;
        return Math.max(0, totalBudget - safeText(recentPart).length() - separatorCost);
    }

    private String renderStructuredContext(String structuredSource,
                                           String summaryFallback,
                                           int budget) {
        if (budget <= 0) {
            return "";
        }
        String source = StrUtil.isNotBlank(structuredSource) ? structuredSource : summaryFallback;
        return clipHead(source, budget);
    }

    private String renderRecentContext(String recentSource, int budget) {
        if (budget <= 0 || StrUtil.isBlank(recentSource)) {
            return "";
        }
        String title = "最近相关对话：\n";
        if (budget <= title.length()) {
            return clipTail(recentSource, budget);
        }
        String body = clipTail(recentSource, budget - title.length());
        if (body.isBlank()) {
            return "";
        }
        return title + body;
    }

    private void appendSection(StringBuilder builder, String title, String content) {
        if (StrUtil.isBlank(content)) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(title).append("：\n")
            .append(content.trim())
            .append("\n\n");
    }

    private void appendBulletSection(StringBuilder builder, String title, List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return;
        }
        StringBuilder sectionBuilder = new StringBuilder();
        values.stream()
            .filter(StrUtil::isNotBlank)
            .limit(limit)
            .forEach(value -> sectionBuilder.append("- ").append(value.trim()).append('\n'));
        if (sectionBuilder.isEmpty()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(title).append("：\n")
            .append(sectionBuilder)
            .append('\n');
    }

    private String clipHead(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        if (maxChars <= 1) {
            return "";
        }
        return normalized.substring(0, maxChars - 1) + "…";
    }

    private String clipTail(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        if (maxChars <= 1) {
            return "";
        }
        int start = Math.max(0, normalized.length() - (maxChars - 1));
        return "…" + normalized.substring(start);
    }

    private String joinNonBlank(String left, String right) {
        if (StrUtil.isBlank(left)) {
            return safeText(right);
        }
        if (StrUtil.isBlank(right)) {
            return safeText(left);
        }
        return left.trim() + "\n\n" + right.trim();
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }
}
