package org.javaup.ai.chatagent.service;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDate;

/**
 * 单次流式会话启动蓝图。
 */
@Data
@AllArgsConstructor
public class StreamLaunchPlan {

    private final String question;

    private final String conversationId;

    private final Long selectedDocumentId;

    private final String selectedDocumentName;

    private final String leaseKey;

    private final String leaseOwnerToken;

    private final LocalDate currentDate;

    private final String currentDateText;
}
