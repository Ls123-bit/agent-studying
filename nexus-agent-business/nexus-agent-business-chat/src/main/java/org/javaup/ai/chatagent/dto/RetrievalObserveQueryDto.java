package org.javaup.ai.chatagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;


@Data
public class RetrievalObserveQueryDto {

    @NotBlank(message = "conversationId 不能为空")
    private String conversationId;

    @NotNull(message = "exchangeId 不能为空")
    private String exchangeId;
}
