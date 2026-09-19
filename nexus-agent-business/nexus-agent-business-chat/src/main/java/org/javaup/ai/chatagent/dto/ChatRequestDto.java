package org.javaup.ai.chatagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;




/*
入口:Controller接受请求
一切从前端的 POST 请求开始。前端把用户的问题、会话 ID、聊天模式等信息打包成 ChatRequestDto，发到 /api/chat/stream 接口。
先看请求参数长什么样
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequestDto {

    @NotBlank(message = "question 不能为空")
    private String question;// 用户输入的问题
    private String conversationId; // 会话 ID，不传则自动生成新会话

    @NotBlank(message = "chatMode 不能为空")
    private String chatMode; // 聊天模式：OPEN_CHAT / AUTO_DOCUMENT / DOCUMENT

    private String selectedDocumentId; // 当前文档问答模式下，用户选择的文档 ID
}
