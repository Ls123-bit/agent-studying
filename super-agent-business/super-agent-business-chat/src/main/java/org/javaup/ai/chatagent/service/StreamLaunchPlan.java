package org.javaup.ai.chatagent.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;

import java.time.LocalDate;

/**
 * 单次流式会话启动蓝图。
 */
@Data
@AllArgsConstructor
public class StreamLaunchPlan {

    private final String question;

    private final String conversationId;

    private final String agentQuestion;

    /**
     * 本轮前置编排后的执行计划。
     */
    private final ConversationExecutionPlan executionPlan;

    private final String leaseKey;

    private final String leaseOwnerToken;

    private final LocalDate currentDate;

    private final String currentDateText;
}
