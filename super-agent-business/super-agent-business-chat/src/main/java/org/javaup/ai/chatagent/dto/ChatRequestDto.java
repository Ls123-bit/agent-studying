package org.javaup.ai.chatagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.enums.ChatQueryMode;

/**
 * 对话请求体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequestDto {

    @NotBlank(message = "question 不能为空")
    private String question;
    private String conversationId;

    /**
     * 当前这轮对话采用哪一种显式模式。
     *
     * <p>这里不再让后端根据 selectedDocumentId 或问题内容去“猜”模式，
     * 而是要求前端把模式明确传进来。
     * 这样链路会非常清楚：
     * - DOCUMENT: 固定走文档 RAG
     * - OPEN_CHAT: 固定走开放式 Agent</p>
     */
    @NotNull(message = "chatMode 不能为空")
    private ChatQueryMode chatMode;

    private String selectedDocumentId;
}
