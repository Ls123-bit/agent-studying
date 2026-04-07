package org.javaup.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文档策略流水线出参。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStrategyPipelineVo {

    /**
     * 流水线类型。
     */
    private String pipelineType;

    /**
     * 流水线类型名称。
     */
    private String pipelineTypeName;

    /**
     * 流水线策略快照。
     */
    private String strategySnapshot;

    /**
     * 流水线步骤。
     */
    private List<DocumentStrategyStepVo> steps;
}
