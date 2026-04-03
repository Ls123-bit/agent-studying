package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.RagRetrievalContext;
import org.javaup.ai.chatagent.rag.model.SubQuestionEvidence;
import org.springframework.stereotype.Service;

import java.util.List;

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

        if (StrUtil.isNotBlank(plan.getHistorySummary())) {
            /*
             * 历史摘要不是每轮都必须塞进 Prompt，
             * 只有当前置编排阶段认为它仍然有价值时，才继续保留。
             */
            builder.append("相关历史上下文：\n").append(plan.getHistorySummary()).append("\n\n");
        }

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
            appendReferences(builder, evidence.getReferences());
        }
        return builder.toString().trim();
    }

    /**
     * 把统一引用对象格式化成 Prompt 片段。
     */
    private void appendReferences(StringBuilder builder, List<SearchReference> references) {
        if (references == null || references.isEmpty()) {
            builder.append("- 当前子问题没有检索到证据\n");
            return;
        }
        for (SearchReference reference : references) {
            /*
             * 不同来源走不同格式化分支：
             * - DOCUMENT：强调文档名、章节、页码
             * - WEB：强调标题、链接、摘要
             */
            if ("WEB".equalsIgnoreCase(reference.getSourceType())) {
                appendWebReference(builder, reference);
                continue;
            }
            builder.append("[")
                .append(reference.getReferenceId())
                .append("] 文档：")
                .append(StrUtil.blankToDefault(reference.getDocumentName(), reference.getTitle()))
                .append("；章节：")
                .append(StrUtil.blankToDefault(reference.getSectionPath(), "未识别"))
                .append("；页码：")
                .append(StrUtil.blankToDefault(reference.getPageNo(), "未知"))
                .append("\n内容：")
                .append(trimSnippet(reference.getSnippet()))
                .append("\n\n");
        }
    }

    /**
     * 格式化网页来源证据。
     *
     * <p>网页来源和文档切片的字段结构不同，
     * 这里单独分支处理，避免把 URL、标题、摘要挤进“章节/页码”那套格式里显得别扭。</p>
     */
    private void appendWebReference(StringBuilder builder, SearchReference reference) {
        builder.append("[")
            .append(reference.getReferenceId())
            .append("] 网页：")
            .append(StrUtil.blankToDefault(reference.getTitle(), "网页来源"))
            .append("；链接：")
            .append(StrUtil.blankToDefault(reference.getUrl(), "未知"))
            .append("\n摘要：")
            .append(trimSnippet(reference.getSnippet()))
            .append("\n\n");
    }

    /**
     * 控制单个片段注入 Prompt 的长度，避免单轮证据过大。
     */
    private String trimSnippet(String snippet) {
        if (StrUtil.isBlank(snippet)) {
            return "";
        }
        /*
         * 这里不是全文截断，而是控制“单条证据注入 Prompt 的最大长度”。
         * 目的不是省流量，而是防止长文片段把有限 Prompt 空间全部占满。
         */
        return snippet.length() <= 1200 ? snippet : snippet.substring(0, 1200) + "...";
    }
}
