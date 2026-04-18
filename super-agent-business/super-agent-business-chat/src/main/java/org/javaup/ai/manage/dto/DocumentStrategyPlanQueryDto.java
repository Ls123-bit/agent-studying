package org.javaup.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 数据传输对象
 * @author: 阿星不是程序员
 **/
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
