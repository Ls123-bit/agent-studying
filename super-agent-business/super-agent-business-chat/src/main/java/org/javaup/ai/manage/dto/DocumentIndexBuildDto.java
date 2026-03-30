package org.javaup.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 构建索引入参。
 */
@Data
public class DocumentIndexBuildDto {

    /**
     * 文档 id。
     */
    @NotNull(message = "文档id不能为空")
    private Long documentId;

    /**
     * 策略方案 id。
     */
    @NotNull(message = "方案id不能为空")
    private Long planId;

    /**
     * 操作人 id。
     */
    private Long operatorId;
}
