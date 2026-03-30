package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 策略方案草稿。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStrategyPlanDraft {

    /**
     * 策略快照。
     */
    private String strategySnapshot;

    /**
     * 推荐原因。
     */
    private String recommendReason;

    /**
     * 草稿步骤列表。
     */
    private List<DocumentStrategyStepDraft> steps;
}
