package org.javaup.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 查询任务日志入参。
 */
@Data
public class DocumentTaskLogQueryDto {

    /**
     * 任务 id。
     */
    @NotNull(message = "任务id不能为空")
    private Long taskId;

    /**
     * 页码，从 1 开始。
     */
    private Integer pageNo;

    /**
     * 每页条数。
     */
    private Integer pageSize;
}
