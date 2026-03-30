package org.javaup.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档策略步骤出参。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStrategyStepVo {

    private Integer stepNo;

    private Integer strategyType;

    private String strategyName;

    private Integer strategyRole;

    private String strategyRoleName;

    private Integer sourceType;

    private String sourceTypeName;

    private Integer executeStatus;

    private String executeStatusName;

    private String recommendReason;
}
