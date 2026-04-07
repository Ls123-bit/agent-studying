package org.javaup.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档策略步骤出参。
 *
 * <p>这个对象描述的是最终生效策略链中的单个步骤，
 * 既会用于“查询策略方案”，也会用于“确认策略方案”的返回结果。</p>
 *
 * <p>和请求入参不同，这里已经是后端标准化后的结构，
 * 会把枚举码和对应文案一起带回去，方便调用方直接理解每个步骤的业务含义。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStrategyStepVo {

    /**
     * 步骤顺序。
     *
     * <p>表示这个步骤在所属流水线中的位置。</p>
     */
    private Integer stepNo;

    /**
     * 流水线类型。
     */
    private String pipelineType;

    /**
     * 流水线类型名称。
     */
    private String pipelineTypeName;

    /**
     * 策略类型码。
     *
     * <p>例如结构切块、递归切块、语义切块、LLM 智能切块等。</p>
     */
    private Integer strategyType;

    /**
     * 策略类型名称。
     *
     * <p>对应 `strategyType` 的人类可读文案。</p>
     */
    private String strategyName;

    /**
     * 策略角色码。
     *
     * <p>用于描述这个策略在链路里的职责，例如主策略、兜底策略、优化策略、增强策略。</p>
     */
    private Integer strategyRole;

    /**
     * 策略角色名称。
     */
    private String strategyRoleName;

    /**
     * 来源类型码。
     *
     * <p>用于区分这一步是系统推荐保留下来的，还是用户手动新增/保留的。</p>
     */
    private Integer sourceType;

    /**
     * 来源类型名称。
     */
    private String sourceTypeName;

    /**
     * 执行状态码。
     *
     * <p>在方案刚确认时通常还是 WAIT_EXECUTE，
     * 等到真正构建索引执行后，才可能推进到执行成功或失败。</p>
     */
    private Integer executeStatus;

    /**
     * 执行状态名称。
     */
    private String executeStatusName;

    /**
     * 推荐原因。
     *
     * <p>说明这一步为什么会出现在策略链中，
     * 既可来自系统推荐理由，也可能延续用户调整前的推荐解释。</p>
     */
    private String recommendReason;
}
