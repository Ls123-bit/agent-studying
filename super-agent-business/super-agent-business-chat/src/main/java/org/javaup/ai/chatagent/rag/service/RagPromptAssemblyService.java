package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.AnswerHistoryContext;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.RagPromptAssemblyResult;
import org.javaup.ai.chatagent.rag.model.RagRetrievalContext;
import org.javaup.ai.chatagent.rag.model.SubQuestionEvidence;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author: 阿星不是程序员
 **/

@Service
public class RagPromptAssemblyService {

    private static final String DEFAULT_SYSTEM_PROMPT = """
        你是 JavaUp 的企业知识问答助手。
        你必须严格基于给定证据回答，不要编造证据中没有出现的事实。
        如果提供了“对话承接上下文”，它只用于理解当前问题中的指代关系，不能替代证据材料，也不能作为事实来源。
        如果证据不足以支持明确结论，请直接说明资料不足。
        如果问题被拆成多个子问题，请按编号逐一回答。
        如果引用了证据，请在对应句子末尾标注 [1][2] 这样的引用编号。
        """;

    private final ChatRagProperties properties;

    public RagPromptAssemblyService(ChatRagProperties properties) {
        this.properties = properties;
    }

    public String buildSystemPrompt() {

        return StrUtil.isNotBlank(properties.getAnswerSystemPrompt())
            ? properties.getAnswerSystemPrompt().trim()
            : DEFAULT_SYSTEM_PROMPT.trim();
    }

    public String buildUserPrompt(ConversationExecutionPlan plan, RagRetrievalContext context) {
        return assemble(plan, context).getUserPrompt();
    }

    public RagPromptAssemblyResult assemble(ConversationExecutionPlan plan, RagRetrievalContext context) {
        StringBuilder builder = new StringBuilder();
        PromptBudget promptBudget = new PromptBudget(
            Math.max(0, properties.getTotalEvidenceMaxChars()),
            Math.max(0, properties.getPerSubQuestionEvidenceMaxChars())
        );
        Set<String> renderedReferenceKeys = new LinkedHashSet<>();

        builder.append("当前日期：").append(plan.getCurrentDateText()).append("\n\n");
        builder.append("用户原始问题：\n").append(plan.getOriginalQuestion()).append("\n\n");

        if (StrUtil.isNotBlank(plan.getRetrievalQuestion()) && !plan.getRetrievalQuestion().equals(plan.getOriginalQuestion())) {

            builder.append("检索理解后的问题：\n").append(plan.getRetrievalQuestion()).append("\n\n");
        }

        appendHistoryContext(builder, plan);

        if (plan.getRetrievalSubQuestions() != null && plan.getRetrievalSubQuestions().size() > 1) {

            builder.append("请按下面这些子问题逐一回答：\n");
            for (int index = 0; index < plan.getRetrievalSubQuestions().size(); index++) {
                builder.append(index + 1).append(". ").append(plan.getRetrievalSubQuestions().get(index)).append("\n");
            }
            builder.append("\n");
        }

        builder.append("证据材料：\n");
        for (SubQuestionEvidence evidence : context.getSubQuestionEvidenceList()) {

            builder.append("\n## 子问题")
                .append(evidence.getSubQuestionIndex())
                .append("：")
                .append(evidence.getSubQuestion())
                .append("\n");
            appendReferences(builder, evidence.getReferences(), renderedReferenceKeys, promptBudget);
        }
        return new RagPromptAssemblyResult(
            buildSystemPrompt(),
            builder.toString().trim(),
            promptBudget.totalBudget,
            promptBudget.perSubQuestionBudget,
            promptBudget.renderedReferenceCount,
            promptBudget.omittedReferenceCount,
            promptBudget.renderedReferenceDetails,
            promptBudget.omittedReferenceDetails
        );
    }

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

            if ("WEB".equalsIgnoreCase(reference.getSourceType())) {
                String block = buildWebReferenceBlock(reference);
                if (promptBudget.tryConsume(block.length())) {
                    builder.append(block);
                    renderedReferenceKeys.add(uniqueKey);
                    promptBudget.markRendered(referenceSummary(reference, "已纳入 Prompt"));
                } else {
                    omitted = true;
                    promptBudget.markOmitted(referenceSummary(reference, "超出上下文预算，已省略"));
                    break;
                }
                continue;
            }
            String block = buildDocumentReferenceBlock(reference);
            if (promptBudget.tryConsume(block.length())) {
                builder.append(block);
                renderedReferenceKeys.add(uniqueKey);
                promptBudget.markRendered(referenceSummary(reference, "已纳入 Prompt"));
            } else {
                omitted = true;
                promptBudget.markOmitted(referenceSummary(reference, "超出上下文预算，已省略"));
                break;
            }
        }
        if (omitted) {
            builder.append("- 其余证据因上下文预算限制已省略\n");
        }
    }

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
            .append("\n内容：")
            .append(trimSnippet(reference.getSnippet(), 1100))
            .append("\n\n")
            .toString();
    }

    private String trimSnippet(String snippet, int maxChars) {
        if (StrUtil.isBlank(snippet)) {
            return "";
        }

        return snippet.length() <= maxChars ? snippet : snippet.substring(0, maxChars) + "...";
    }

    private void appendHistoryContext(StringBuilder builder, ConversationExecutionPlan plan) {
        AnswerHistoryContext answerHistoryContext = plan.getAnswerHistoryContext();
        if (answerHistoryContext == null || answerHistoryContext.isEmpty()) {
            return;
        }

        builder.append(answerHistoryContext.getRenderedText().trim()).append("\n\n");
    }

    private String referenceSummary(SearchReference reference, String suffix) {
        if (reference == null) {
            return suffix;
        }
        String title = StrUtil.blankToDefault(reference.getDocumentName(), reference.getTitle());
        String path = StrUtil.blankToDefault(reference.getSectionPath(), reference.getUrl());
        String refId = StrUtil.blankToDefault(reference.getReferenceId(), "-");
        return "[" + refId + "] " + title + (StrUtil.isBlank(path) ? "" : " | " + path) + " | " + suffix;
    }

    private static final class PromptBudget {

        private final int totalBudget;
        private final int perSubQuestionBudget;
        private int remainingTotal;
        private int remainingSubQuestion;
        private int renderedReferenceCount;
        private int omittedReferenceCount;
        private final List<String> renderedReferenceDetails = new ArrayList<>();
        private final List<String> omittedReferenceDetails = new ArrayList<>();

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

        private void markRendered(String detail) {
            renderedReferenceCount++;
            if (StrUtil.isNotBlank(detail)) {
                renderedReferenceDetails.add(detail);
            }
        }

        private void markOmitted(String detail) {
            omittedReferenceCount++;
            if (StrUtil.isNotBlank(detail)) {
                omittedReferenceDetails.add(detail);
            }
        }
    }
}
