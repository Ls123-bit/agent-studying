package org.javaup.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 查询文档策略方案入参。
 */
@Data
public class DocumentStrategyPlanQueryDto {

    /**
     * 文档 id。
     */
    @NotNull(message = "文档id不能为空")
    private Long documentId;
}
