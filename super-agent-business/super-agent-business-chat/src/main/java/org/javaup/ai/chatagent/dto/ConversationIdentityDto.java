package org.javaup.ai.chatagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 会话标识入参。
 */
@Data
public class ConversationIdentityDto {

    /**
     * 会话唯一标识。
     */
    @NotBlank(message = "conversationId 不能为空")
    private String conversationId;
}
