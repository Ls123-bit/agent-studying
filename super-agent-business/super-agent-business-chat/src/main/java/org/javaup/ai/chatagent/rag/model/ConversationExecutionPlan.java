package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.enums.ChatQueryMode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 单轮对话执行计划。
 *
 * <p>这个对象是“前置编排”和“最终执行器”之间的契约：
 * 编排器负责尽可能把模式确定、历史加载、问题改写这些工作前置完成，
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
     * 当前这一轮由前端显式指定的提问模式。
     */
    private ChatQueryMode chatMode;

    /**
     * 用户原始问题。
     */
    private String originalQuestion;

    /**
     * 供 Agent 使用的增强问题。
     */
    private String agentQuestion;

    /**
     * 改写阶段产出的独立问题。
     */
    private String rewriteQuestion;

    /**
     * 改写阶段产出的子问题拆分。
     */
    @Builder.Default
    private List<String> rewriteSubQuestions = new ArrayList<>();

    /**
     * 检索阶段真正执行的主问题。
     */
    private String retrievalQuestion;

    /**
     * 检索阶段真正执行的子问题列表。
     */
    @Builder.Default
    private List<String> retrievalSubQuestions = new ArrayList<>();

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
     *
     * <p>这是回答历史装配的原始材料之一，
     * 不是最终直接送进 Prompt 的文本。</p>
     */
    private String answerRecentTranscript;

    /**
     * 回答阶段最终使用的历史上下文。
     *
     * <p>这份对象由前置编排阶段生成，
     * Prompt 装配层只负责消费它，不再自己重复裁剪。</p>
     */
    private AnswerHistoryContext answerHistoryContext;

    /**
     * 检索阶段使用的多轮追问锚点上下文。
     */
    private RetrievalAnchorContext retrievalAnchorContext;

    /**
     * 文档问答模式下的会话关系解析结果。
     *
     * <p>这份结果不是最终检索请求本身，
     * 而是“当前问题和上文是什么关系”的结构化判断，
     * 方便调试页解释为什么后面会生成当前这份检索计划。</p>
     */
    private ConversationIntentResolution intentResolution;

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
     * 本轮真正用于检索的文档主键。
     */
    private Long selectedDocumentId;

    /**
     * 与 selectedDocumentId 对应的有效索引任务主键。
     */
    private Long selectedTaskId;

    /**
     * 没有证据时的兜底回复。
     */
    private String noEvidenceReply;
}
