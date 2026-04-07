package org.javaup.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档策略方案出参。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStrategyPlanVo {

    private Long planId;

    private Integer planVersion;

    private Integer planSource;

    private String planSourceName;

    private Integer planStatus;

    private String planStatusName;

    private String strategySnapshot;

    private String recommendReason;

    private DocumentStrategyPipelineVo parentPipeline;

    private DocumentStrategyPipelineVo childPipeline;
}
