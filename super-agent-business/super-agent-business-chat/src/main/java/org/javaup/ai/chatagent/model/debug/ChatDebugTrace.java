package org.javaup.ai.chatagent.model.debug;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.chatagent.rag.model.ConversationNavigationState;
import org.javaup.ai.chatagent.rag.model.ConversationIntentResolution;
import org.javaup.enums.ChatQueryMode;

import java.util.ArrayList;
import java.util.List;

/**
 * 单轮对话调试轨迹。
 *
 * <p>这个对象面向“排查和教学观测”，
 * 目的是把一次回答前后的关键决策节点完整留痕：</p>
 * <p>1. 前端选的是哪种提问模式。</p>
 * <p>2. 问题改写成了什么。</p>
 * <p>3. 实际检索了哪些文档和通道。</p>
 * <p>4. 检索和重排经历了哪些步骤。</p>
 * <p>5. 最终喂给模型的 Prompt 大致长什么样。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatDebugTrace {

    /**
     * 最终执行模式。
     */
    private String executionMode;

    /**
     * 当前轮显式提问模式。
     */
    private ChatQueryMode chatMode;

    /**
     * 用户原始问题。
     */
    private String originalQuestion;

    /**
     * rewrite 阶段产出的独立问题。
     */
    private String rewriteQuestion;

    /**
     * rewrite 阶段产出的子问题拆分。
     */
    @Builder.Default
    private List<String> rewriteSubQuestions = new ArrayList<>();

    /**
     * 最终真正执行检索的主问题。
     *
     * <p>兼容历史调试轨迹里旧的 rewrittenQuestion 字段。</p>
     */
    @JsonAlias("rewrittenQuestion")
    private String retrievalQuestion;

    /**
     * 给 Agent 路径使用的增强问题。
     */
    private String agentQuestion;

    /**
     * 文档问答模式下的会话关系解析结果。
     */
    private ConversationIntentResolution intentResolution;

    /**
     * 当前轮四层导航锚点状态。
     */
    private ConversationNavigationState navigationState;

    /**
     * 历史摘要。
     */
    private String historySummary;

    /**
     * 长期摘要文本。
     */
    private String longTermSummary;

    /**
     * 最近几轮原文窗口。
     */
    private String recentHistoryTranscript;

    /**
     * 回答阶段原始最近上下文。
     */
    private String answerRecentTranscript;

    /**
     * 回答阶段最终历史上下文。
     */
    private String answerHistoryContext;

    /**
     * 回答阶段是否判定为承接式追问。
     */
    private boolean answerHistoryFollowUpQuestion;

    /**
     * 检索阶段是否应用了追问锚点。
     */
    private boolean retrievalAnchorApplied;

    /**
     * 检索锚点阶段解析出的主问题。
     */
    private String retrievalAnchorResolvedQuestion;

    /**
     * 检索阶段根主题。
     */
    private String retrievalAnchorRootTopic;

    /**
     * 检索阶段根主题章节编码。
     */
    private String retrievalAnchorRootSectionCode;

    /**
     * 检索阶段根主题章节标题。
     */
    private String retrievalAnchorRootSectionTitle;

    /**
     * 检索阶段当前面向。
     */
    private String retrievalAnchorFacet;

    /**
     * 检索阶段当前目标章节提示。
     */
    private String retrievalAnchorTargetSectionHint;

    /**
     * 检索阶段当前引用的编号项下标。
     */
    private Integer retrievalAnchorItemIndex;

    /**
     * 检索阶段当前引用的编号项文本。
     */
    private String retrievalAnchorItemText;

    /**
     * 是否启用了长期摘要压缩。
     */
    private boolean historyCompressionApplied;

    /**
     * 长期摘要已覆盖到的最后一条 exchangeId。
     */
    private Long historyCoveredExchangeId;

    /**
     * 长期摘要已覆盖的轮次数。
     */
    private Integer historyCoveredExchangeCount;

    /**
     * 长期摘要累计压缩次数。
     */
    private Integer historyCompressionCount;

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
     * 最终真正执行检索的子问题列表。
     *
     * <p>兼容历史调试轨迹里旧的 subQuestions 字段。</p>
     */
    @JsonAlias("subQuestions")
    @Builder.Default
    private List<String> retrievalSubQuestions = new ArrayList<>();

    /**
     * 实际用于检索的文档主键。
     */
    private Long selectedDocumentId;

    /**
     * 实际用于检索的索引任务主键。
     */
    private Long selectedTaskId;

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
     * 开放式 Agent 或外部工具的调用轨迹。
     */
    @Builder.Default
    private List<ChatToolTrace> toolTraces = new ArrayList<>();

    /**
     * 模型调用使用量轨迹。
     */
    @Builder.Default
    private List<ChatModelUsageTrace> modelUsageTraces = new ArrayList<>();

    /**
     * 调用限制统计。
     */
    private ChatLimitStats limitStats;

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
