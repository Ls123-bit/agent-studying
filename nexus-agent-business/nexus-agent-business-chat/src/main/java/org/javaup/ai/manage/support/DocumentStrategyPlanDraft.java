package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/*
最终返回的 DocumentStrategyPlanDraft 包含四个东西：
strategySnapshot：紧凑的快照字符串，格式如 PARENT:1;CHILD:4,2
recommendReason：所有推荐理由拼接成的文本
parentSteps：父块流水线的步骤草案列表
childSteps：子块流水线的步骤草案列表
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStrategyPlanDraft {

    private String strategySnapshot;

    private String recommendReason;

    private List<DocumentStrategyStepDraft> parentSteps;

    private List<DocumentStrategyStepDraft> childSteps;
}
