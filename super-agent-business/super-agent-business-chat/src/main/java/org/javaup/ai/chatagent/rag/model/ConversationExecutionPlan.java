package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.enums.ChatRouteType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 单轮对话执行计划。
 *
 * <p>这个对象是“前置编排”和“最终执行器”之间的契约：
 * 编排器负责尽可能把路由、改写、知识域收缩这些工作前置完成，
 * 执行器只关心如何基于这份计划真正流式输出。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationExecutionPlan {

    /**
     * 最终执行模式。
     */
    private ExecutionMode mode;

    /**
     * 路由类型。
     */
    private ChatRouteType routeType;

    /**
     * 用户原始问题。
     */
    private String originalQuestion;

    /**
     * 供 Agent 使用的增强问题。
     */
    private String agentQuestion;

    /**
     * 改写后的独立问题。
     */
    private String rewrittenQuestion;

    /**
     * 子问题列表。
     */
    @Builder.Default
    private List<String> subQuestions = new ArrayList<>();

    /**
     * 当前轮历史摘要。
     */
    private String historySummary;

    /**
     * 长期摘要文本。
     */
    private String longTermSummary;

    /**
     * 历史结构化上下文。
     */
    @Builder.Default
    private HistoryPlanningContext historyPlanningContext = new HistoryPlanningContext();

    /**
     * 最近几轮原文窗口。
     */
    private String recentHistoryTranscript;

    /**
     * 回答阶段可安全复用的最近上下文。
     */
    private String answerRecentTranscript;

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
     * 当前日期。
     */
    private LocalDate currentDate;

    /**
     * 当前日期中文文本。
     */
    private String currentDateText;

    /**
     * 是否需要优先联网 / 实时能力。
     */
    private boolean requiresFreshSearch;

    /**
     * 是否需要基于当前日期解释问题。
     */
    private boolean requiresCurrentDateAnchoring;

    /**
     * 本轮澄清提示语。
     */
    private String clarifyPrompt;

    /**
     * 命中的知识域候选项。
     */
    @Builder.Default
    private List<KnowledgeScopeOption> scopeOptions = new ArrayList<>();

    /**
     * 本轮真正用于检索的文档主键列表。
     */
    @Builder.Default
    private List<Long> selectedDocumentIds = new ArrayList<>();

    /**
     * 与 selectedDocumentIds 对应的有效索引任务列表。
     */
    @Builder.Default
    private List<Long> selectedTaskIds = new ArrayList<>();

    /**
     * 没有证据时的兜底回复。
     */
    private String noEvidenceReply;
}
