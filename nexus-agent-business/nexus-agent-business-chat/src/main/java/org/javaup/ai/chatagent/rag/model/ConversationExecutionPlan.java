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
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 单轮对话执行计划
 * @author: 阿星不是程序员
 **/
/*
执行计划（数据模型）
准备阶段的最终产物，包含了执行器需要的所有信息。
主要字段包括：
执行模式（ExecutionMode）
原始问题和改写后的问题
子问题列表
选中的文档信息
记忆上下文（历史摘要、最近对话）
导航决策（DocumentNavigationDecision）
结构锚点和条目锚点
当前日期信息
Agent 问题（最终发给模型的完整问题）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationExecutionPlan {

    private ExecutionMode mode;//最影响聊天架构与执行入口主线的是mode 它决定下一步去哪个执行器。

    private ChatQueryMode chatMode;

    private String originalQuestion;

    private String agentQuestion;

    private String rewriteQuestion;

    @Builder.Default
    private List<String> rewriteSubQuestions = new ArrayList<>();

    private String retrievalQuestion;

    @Builder.Default
    private List<String> retrievalSubQuestions = new ArrayList<>();

    private String historySummary;

    private String longTermSummary;

    @Builder.Default
    private HistoryPlanningContext historyPlanningContext = new HistoryPlanningContext();

    private String recentHistoryTranscript;

    private String answerRecentTranscript;

    private AnswerHistoryContext answerHistoryContext;

    private DocumentNavigationDecision navigationDecision;

    private boolean historyCompressionApplied;

    private Long historyCoveredExchangeId;

    private Integer historyCoveredExchangeCount;

    private Integer historyCompressionCount;

    private LocalDate currentDate;

    private String currentDateText;

    private boolean requiresFreshSearch;

    private boolean requiresCurrentDateAnchoring;

    private Long selectedDocumentId;

    private String selectedDocumentName;

    private Long selectedTaskId;

    @Builder.Default
    private List<Long> retrievalDocumentIds = new ArrayList<>();

    @Builder.Default
    private List<Long> retrievalTaskIds = new ArrayList<>();

    private String clarificationReply;

    @Builder.Default
    private List<String> clarificationOptions = new ArrayList<>();

    private String clarificationReason;

    private String noEvidenceReply;
}
