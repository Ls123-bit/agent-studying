package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 策略步骤草稿。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStrategyStepDraft {

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
