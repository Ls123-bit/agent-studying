package org.javaup.ai.chatagent.model.debug;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.chatagent.rag.model.KnowledgeScopeOption;

import java.util.ArrayList;
import java.util.List;

/**
 * 单轮对话调试轨迹。
 *
 * <p>这个对象面向“排查和教学观测”，
 * 目的是把一次回答前后的关键决策节点完整留痕：</p>
 * <p>1. 路由为什么这么判。</p>
 * <p>2. 问题改写成了什么。</p>
 * <p>3. 命中了哪些知识域候选。</p>
 * <p>4. 检索和重排经历了哪些步骤。</p>
 * <p>5. 最终喂给模型的 Prompt 大致长什么样。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatDebugTrace {

    /**
     * 路由类型。
     */
    private String routeType;

    /**
     * 最终执行模式。
     */
    private String executionMode;

    /**
     * 用户原始问题。
     */
    private String originalQuestion;

    /**
     * 改写后的问题。
     */
    private String rewrittenQuestion;

    /**
     * 给 Agent 路径使用的增强问题。
     */
    private String agentQuestion;

    /**
     * 历史摘要。
     */
    private String historySummary;

    /**
     * 当前日期文本。
     */
    private String currentDateText;

    /**
     * 是否要求优先联网。
     */
    private boolean requiresFreshSearch;

    /**
     * 是否要求按当前日期锚定问题。
     */
    private boolean requiresCurrentDateAnchoring;

    /**
     * 澄清提示语。
     */
    private String clarifyPrompt;

    /**
     * 子问题列表。
     */
    @Builder.Default
    private List<String> subQuestions = new ArrayList<>();

    /**
     * 知识域候选项。
     */
    @Builder.Default
    private List<KnowledgeScopeOption> scopeOptions = new ArrayList<>();

    /**
     * 实际用于检索的文档主键列表。
     */
    @Builder.Default
    private List<Long> selectedDocumentIds = new ArrayList<>();

    /**
     * 实际用于检索的索引任务列表。
     */
    @Builder.Default
    private List<Long> selectedTaskIds = new ArrayList<>();

    /**
     * 检索和执行备注。
     */
    @Builder.Default
    private List<String> retrievalNotes = new ArrayList<>();

    /**
     * 本轮实际使用的检索通道。
     */
    @Builder.Default
    private List<String> usedChannels = new ArrayList<>();

    /**
     * RAG 回答阶段系统提示词。
     */
    private String ragSystemPrompt;

    /**
     * RAG 回答阶段用户提示词。
     */
    private String ragUserPrompt;

    /**
     * 无证据兜底文案。
     */
    private String noEvidenceReply;
}
