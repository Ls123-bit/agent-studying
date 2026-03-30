package org.javaup.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 文档策略步骤入参项。
 */
@Data
public class DocumentStrategyStepItemDto {

    /**
     * 步骤顺序。
     */
    private Integer stepNo;

    /**
     * 策略类型。
     */
    @NotNull(message = "策略类型不能为空")
    private Integer strategyType;
}
