package org.javaup.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 数据传输对象
 * @author: 阿星不是程序员
 **/
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
