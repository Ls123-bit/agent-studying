package org.javaup.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;


@Data
public class DocumentTaskLogQueryDto {

    @NotNull(message = "任务id不能为空")
    private Long taskId;

    private Integer pageNo;

    private Integer pageSize;
}
