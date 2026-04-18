package org.javaup.ai.manage.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 数据传输对象
 * @author: 阿星不是程序员
 **/
/**
 * 确认文档策略入参。
 *
 * <p>这个 DTO 对应的是“用户最终拍板后的策略方案”，
 * 不是系统推荐方案本身。</p>
 *
 * <p>也就是说，当前端调用 `confirmStrategy` 时，提交的是：</p>
 * <p>1. 用户基于哪一版推荐方案进行操作。</p>
 * <p>2. 用户最终确认后的父块 / 子块双流水线。</p>
 * <p>3. 是否附带了人工调整说明，以及是谁操作的。</p>
 *
 * <p>后端会基于这个 DTO 判断：</p>
 * <p>1. 用户是不是基于当前最新方案在确认，避免旧页面覆盖新状态。</p>
 * <p>2. 最终双流水线是否和原推荐方案一致。</p>
 * <p>3. 是直接确认原方案，还是派生一版新的用户确认方案。</p>
 */
@Data
public class DocumentStrategyConfirmDto {

    /**
     * 文档 id。
     *
     * <p>用于定位当前正在确认方案的是哪一份文档。</p>
     * <p>后端会先根据它读取文档主记录，并校验该文档是否已经解析成功。</p>
     */
    @NotNull(message = "文档id不能为空")
    private Long documentId;

    /**
     * 基础方案 id。
     *
     * <p>表示“用户当前看到并基于其进行调整的那一版方案”。</p>
     *
     * <p>这个字段非常关键，因为后端会拿它和 `document.currentPlanId` 做比较：</p>
     * <p>1. 如果相同，说明用户是在基于当前最新方案确认。</p>
     * <p>2. 如果不同，说明用户页面已经过期，不能直接覆盖当前状态。</p>
     */
    @NotNull(message = "基础方案id不能为空")
    private Long basePlanId;

    /**
     * 调整说明。
     *
     * <p>用于记录用户为什么调整了推荐方案。</p>
     * <p>这个字段不会影响策略计算，但会被保存到方案记录和任务日志里，方便后续复盘。</p>
     */
    private String adjustNote;

    /**
     * 操作人 id。
     *
     * <p>用于记录是谁执行了这次确认动作。</p>
     * <p>后端会把它写入确认方案和任务日志，用于审计和轨迹回溯。</p>
     */
    private Long operatorId;

    /**
     * 最终确认的父块流水线步骤。
     *
     * <p>它表达的是“最终如何生成回答阶段父块”。</p>
     */
    @Valid
    @NotEmpty(message = "父块流水线不能为空")
    private List<DocumentStrategyStepItemDto> parentSteps;

    /**
     * 最终确认的子块流水线步骤。
     *
     * <p>它表达的是“最终如何生成召回阶段子块”，而不是“系统最初推荐了什么”。</p>
     *
     * <p>后端会对这组步骤做标准化处理：</p>
     * <p>1. 按 `stepNo` 排序，保留用户最终顺序。</p>
     * <p>2. 过滤非法策略类型。</p>
     * <p>3. 去重。</p>
     * <p>4. 生成最终生效的子块流水线。</p>
     */
    @Valid
    @NotEmpty(message = "子块流水线不能为空")
    private List<DocumentStrategyStepItemDto> childSteps;
}
