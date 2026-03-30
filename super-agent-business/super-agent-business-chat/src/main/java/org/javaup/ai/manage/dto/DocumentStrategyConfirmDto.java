package org.javaup.ai.manage.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 确认文档策略入参。
 */
@Data
public class DocumentStrategyConfirmDto {

    /**
     * 文档 id。
     */
    @NotNull(message = "文档id不能为空")
    private Long documentId;

    /**
     * 基础方案 id。
     */
    @NotNull(message = "基础方案id不能为空")
    private Long basePlanId;

    /**
     * 调整说明。
     */
    private String adjustNote;

    /**
     * 操作人 id。
     */
    private Long operatorId;

    /**
     * 最终确认的策略步骤。
     */
    @Valid
    @NotEmpty(message = "策略步骤不能为空")
    private List<DocumentStrategyStepItemDto> steps;
}
