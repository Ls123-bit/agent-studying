package org.javaup.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 文档策略步骤入参项。
 *
 * <p>这是 `DocumentStrategyConfirmDto.steps` 里的单个步骤项，
 * 表示最终策略链中的一个节点。</p>
 *
 * <p>后端不会直接信任前端传来的完整步骤对象，
 * 而是主要读取这里的两个字段：</p>
 * <p>1. `stepNo`：表示用户最终确认的执行顺序。</p>
 * <p>2. `strategyType`：表示这一位上到底要执行哪种策略。</p>
 *
 * <p>随后后端会把这组轻量 DTO 规范化成真正的策略步骤实体。</p>
 */
@Data
public class DocumentStrategyStepItemDto {

    /**
     * 步骤顺序。
     *
     * <p>用于表达用户最终拖拽或调整后的顺序。</p>
     *
     * <p>在 `confirmStrategy` 里，后端会优先按这个字段排序，
     * 而不是完全依赖 JSON 数组原始顺序。</p>
     *
     * <p>这样做是为了避免请求经过中间层处理后，数组顺序出现歧义时影响最终执行链。</p>
     */
    private Integer stepNo;

    /**
     * 策略类型。
     *
     * <p>表示当前步骤要执行的策略种类，例如结构切块、递归切块、语义切块或 LLM 智能切块。</p>
     *
     * <p>后端会基于这个字段：</p>
     * <p>1. 判断该策略是否合法。</p>
     * <p>2. 去除重复策略。</p>
     * <p>3. 构建最终的有序策略链。</p>
     */
    @NotNull(message = "策略类型不能为空")
    private Integer strategyType;
}
