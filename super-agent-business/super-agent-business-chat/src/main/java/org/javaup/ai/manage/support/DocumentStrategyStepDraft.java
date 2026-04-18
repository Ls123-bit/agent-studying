package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/
/**
 * 策略步骤草稿。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStrategyStepDraft {

    /**
     * 流水线类型。
     */
    private String pipelineType;

    /**
     * 策略类型。
     */
    private Integer strategyType;

    /**
     * 策略角色。
     */
    private Integer strategyRole;

    /**
     * 来源类型。
     */
    private Integer sourceType;

    /**
     * 推荐原因。
     */
    private String recommendReason;
}
