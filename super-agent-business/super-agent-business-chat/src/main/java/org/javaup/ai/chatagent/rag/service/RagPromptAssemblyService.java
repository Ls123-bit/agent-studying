package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.HistoryPlanningContext;
import org.javaup.ai.chatagent.rag.model.RagRetrievalContext;
import org.javaup.ai.chatagent.rag.model.SubQuestionEvidence;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG Prompt 装配服务。
 *
 * <p>这一层只负责把已经整理好的检索证据组装成稳定 Prompt，
 * 不再参与路由、检索和执行器选择，职责保持单一。</p>
 */
@Service
public class RagPromptAssemblyService {

    private static final String DEFAULT_SYSTEM_PROMPT = """
        你是 JavaUp 的企业知识问答助手。
        你必须严格基于给定证据回答，不要编造证据中没有出现的事实。
        如果证据不足以支持明确结论，请直接说明资料不足。
        如果问题被拆成多个子问题，请按编号逐一回答。
        如果引用了证据，请在对应句子末尾标注 [1][2] 这样的引用编号。
        """;

    private final ChatRagProperties properties;

    public RagPromptAssemblyService(ChatRagProperties properties) {
        this.properties = properties;
    }

    /**
     * 构造系统提示词。
     */
    public String buildSystemPrompt() {
        /*
         * 业务配置优先于代码默认值。
         * 这样线上如果想微调知识问答风格，只改配置，不需要重新改代码。
         */
        return StrUtil.isNotBlank(properties.getAnswerSystemPrompt())
            ? properties.getAnswerSystemPrompt().trim()
            : DEFAULT_SYSTEM_PROMPT.trim();
    }

    /**
     * 构造用户提示词。
     */
    public String buildUserPrompt(ConversationExecutionPlan plan, RagRetrievalContext context) {
        StringBuilder builder = new StringBuilder();
        PromptBudget promptBudget = new PromptBudget(
            Math.max(0, properties.getTotalEvidenceMaxChars()),
            Math.max(0, properties.getPerSubQuestionEvidenceMaxChars())
        );
        Set<String> renderedReferenceKeys = new LinkedHashSet<>();
        /*
         * 当前日期被放在最前面，是为了让模型在处理“今天/最新/当前”这类问题时，
         * 先拿到统一的时间锚点，再去理解后面的证据材料。
         */
        builder.append("当前日期：").append(plan.getCurrentDateText()).append("\n\n");
        builder.append("用户原始问题：\n").append(plan.getOriginalQuestion()).append("\n\n");

        if (StrUtil.isNotBlank(plan.getRewrittenQuestion()) && !plan.getRewrittenQuestion().equals(plan.getOriginalQuestion())) {
            /*
             * 只有当改写结果和原始问题不同，才把“检索理解后的问题”显式暴露给模型。
             * 这样既能保留检索语义，又不会在无需改写时增加无意义噪音。
             */
            builder.append("检索理解后的问题：\n").append(plan.getRewrittenQuestion()).append("\n\n");
        }

        appendHistoryContext(builder, plan);

        if (plan.getSubQuestions() != null && plan.getSubQuestions().size() > 1) {
            /*
             * 多子问题场景下，先把问题编号列出来，
             * 目的是显式告诉模型“需要逐一回答”，避免它只答其中一部分。
             */
            builder.append("请按下面这些子问题逐一回答：\n");
            for (int index = 0; index < plan.getSubQuestions().size(); index++) {
                builder.append(index + 1).append(". ").append(plan.getSubQuestions().get(index)).append("\n");
            }
            builder.append("\n");
        }

        builder.append("证据材料：\n");
        for (SubQuestionEvidence evidence : context.getSubQuestionEvidenceList()) {
            /*
             * 这里刻意保留“子问题 -> 证据”的分区结构。
             * 如果直接把所有证据平铺拼接，模型会更容易混淆不同子问题对应的依据。
             */
            builder.append("\n## 子问题")
                .append(evidence.getSubQuestionIndex())
                .append("：")
                .append(evidence.getSubQuestion())
                .append("\n");
            appendReferences(builder, evidence.getReferences(), renderedReferenceKeys, promptBudget);
        }
        return builder.toString().trim();
    }

    /**
     * 把统一引用对象格式化成 Prompt 片段。
     */
    private void appendReferences(StringBuilder builder,
                                  List<SearchReference> references,
                                  Set<String> renderedReferenceKeys,
                                  PromptBudget promptBudget) {
        if (references == null || references.isEmpty()) {
            builder.append("- 当前子问题没有检索到证据\n");
            return;
        }
        promptBudget.resetSubQuestionBudget();
        boolean omitted = false;
        for (SearchReference reference : references) {
            String uniqueKey = reference.uniqueKey();
            if (renderedReferenceKeys.contains(uniqueKey)) {
                String reuseLine = "- 复用证据 [" + reference.getReferenceId() + "]\n";
                if (promptBudget.tryConsume(reuseLine.length())) {
                    builder.append(reuseLine);
                }
                continue;
            }
            /*
             * 不同来源走不同格式化分支：
             * - DOCUMENT：强调文档名、章节、页码
             * - WEB：强调标题、链接、摘要
             */
            if ("WEB".equalsIgnoreCase(reference.getSourceType())) {
                String block = buildWebReferenceBlock(reference);
                if (promptBudget.tryConsume(block.length())) {
                    builder.append(block);
                    renderedReferenceKeys.add(uniqueKey);
                } else {
                    omitted = true;
                    break;
                }
                continue;
            }
            String block = buildDocumentReferenceBlock(reference);
            if (promptBudget.tryConsume(block.length())) {
                builder.append(block);
                renderedReferenceKeys.add(uniqueKey);
            } else {
                omitted = true;
                break;
            }
        }
        if (omitted) {
            builder.append("- 其余证据因上下文预算限制已省略\n");
        }
    }

    /**
     * 格式化网页来源证据。
     *
     * <p>网页来源和文档切片的字段结构不同，
     * 这里单独分支处理，避免把 URL、标题、摘要挤进“章节/页码”那套格式里显得别扭。</p>
     */
    private String buildWebReferenceBlock(SearchReference reference) {
        return new StringBuilder("[")
            .append(reference.getReferenceId())
            .append("] 网页：")
            .append(StrUtil.blankToDefault(reference.getTitle(), "网页来源"))
            .append("；链接：")
            .append(StrUtil.blankToDefault(reference.getUrl(), "未知"))
            .append("\n摘要：")
            .append(trimSnippet(reference.getSnippet(), 900))
            .append("\n\n")
            .toString();
    }

    private String buildDocumentReferenceBlock(SearchReference reference) {
        return new StringBuilder("[")
            .append(reference.getReferenceId())
            .append("] 文档：")
            .append(StrUtil.blankToDefault(reference.getDocumentName(), reference.getTitle()))
            .append("；章节：")
            .append(StrUtil.blankToDefault(reference.getSectionPath(), "未识别"))
            .append("；页码：")
            .append(StrUtil.blankToDefault(reference.getPageNo(), "未知"))
            .append("\n内容：")
            .append(trimSnippet(reference.getSnippet(), 1100))
            .append("\n\n")
            .toString();
    }

    /**
     * 控制单个片段注入 Prompt 的长度，避免单轮证据过大。
     */
    private String trimSnippet(String snippet, int maxChars) {
        if (StrUtil.isBlank(snippet)) {
            return "";
        }
        /*
         * 这里不是全文截断，而是控制“单条证据注入 Prompt 的最大长度”。
         * 目的不是省流量，而是防止长文片段把有限 Prompt 空间全部占满。
         */
        return snippet.length() <= maxChars ? snippet : snippet.substring(0, maxChars) + "...";
    }

    private void appendHistoryContext(StringBuilder builder, ConversationExecutionPlan plan) {
        StringBuilder historyBuilder = new StringBuilder();
        HistoryPlanningContext historyPlanningContext = plan.getHistoryPlanningContext();
        boolean appended = false;
        if (historyPlanningContext != null) {
            /*
             * 回答阶段不再直接把整段 assembledHistory 原样塞给模型，
             * 而是优先注入结构化历史要点。
             *
             * 这样做的目的不是“让 prompt 更花哨”，而是减少两类噪音：
             * 1. 路由/改写阶段已经不需要的长篇历史原文
             * 2. 长期摘要里那些对当前回答帮助不大的描述性文字
             *
             * 现在模型先看到的是：
             * - 长期目标
             * - 已确认事实
             * - 待跟进问题
             * - 检索提示
             * 然后再看到最近原文窗口，信息密度会更高。
             */
            if (StrUtil.isNotBlank(historyPlanningContext.getConversationGoal())) {
                historyBuilder.append("相关会话目标：\n")
                    .append(historyPlanningContext.getConversationGoal())
                    .append("\n\n");
                appended = true;
            }
            appended = appendBulletBlock(historyBuilder, "已确认事实", historyPlanningContext.getStableFacts()) || appended;
            appended = appendBulletBlock(historyBuilder, "待跟进问题", historyPlanningContext.getPendingQuestions()) || appended;
            appended = appendBulletBlock(historyBuilder, "检索提示", historyPlanningContext.getRetrievalHints()) || appended;
        }
        if (StrUtil.isNotBlank(plan.getAnswerRecentTranscript())) {
            historyBuilder.append("最近相关对话：\n")
                .append(plan.getAnswerRecentTranscript())
                .append("\n\n");
            appended = true;
        }
        if (!appended && StrUtil.isNotBlank(plan.getHistorySummary())) {
            historyBuilder.append("相关历史上下文：\n").append(plan.getHistorySummary()).append("\n\n");
        }
        String historyText = historyBuilder.toString().trim();
        if (StrUtil.isBlank(historyText)) {
            return;
        }
        int maxChars = Math.max(1, properties.getAnswerHistoryMaxChars());
        if (historyText.length() > maxChars) {
            historyText = historyText.substring(0, maxChars - 1) + "…";
        }
        builder.append(historyText).append("\n\n");
    }

    private boolean appendBulletBlock(StringBuilder builder, String title, List<String> values) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        /*
         * 这里统一限制最多 5 条，是为了避免历史结构化字段本身又膨胀成新的 prompt 噪音源。
         * 当前回答阶段真正需要的是“最值得保留的少量要点”，不是完整会话知识图谱。
         */
        builder.append(title).append("：\n");
        values.stream()
            .filter(StrUtil::isNotBlank)
            .limit(5)
            .forEach(value -> builder.append("- ").append(value.trim()).append("\n"));
        builder.append("\n");
        return true;
    }

    private static final class PromptBudget {

        private final int totalBudget;
        private final int perSubQuestionBudget;
        private int remainingTotal;
        private int remainingSubQuestion;

        private PromptBudget(int totalBudget, int perSubQuestionBudget) {
            this.totalBudget = totalBudget;
            this.perSubQuestionBudget = perSubQuestionBudget;
            this.remainingTotal = totalBudget;
            this.remainingSubQuestion = perSubQuestionBudget;
        }

        private void resetSubQuestionBudget() {
            this.remainingSubQuestion = perSubQuestionBudget;
        }

        private boolean tryConsume(int size) {
            if (totalBudget <= 0 || perSubQuestionBudget <= 0) {
                return false;
            }
            if (size > remainingTotal || size > remainingSubQuestion) {
                return false;
            }
            remainingTotal -= size;
            remainingSubQuestion -= size;
            return true;
        }
    }
}
