package org.javaup.ai.chatagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;


@Data
public class ConversationIdentityDto {

    @NotBlank(message = "conversationId 不能为空")
    private String conversationId;
}
