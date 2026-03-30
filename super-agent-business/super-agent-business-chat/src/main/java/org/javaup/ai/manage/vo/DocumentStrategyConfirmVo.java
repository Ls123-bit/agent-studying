package org.javaup.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 确认文档策略出参。
 *
 * <p>这个对象表达的是“本次确认动作完成后，最终落地成了哪一版生效方案”。</p>
 *
 * <p>它不是把数据库里的所有策略方案信息原样返回出来，
 * 而是聚焦回答下面几个问题：</p>
 * <p>1. 当前文档最后确认的是哪一版方案。</p>
 * <p>2. 当前策略状态是否已经进入 CONFIRMED。</p>
 * <p>3. 服务端有没有对用户输入做规范化处理。</p>
 * <p>4. 最终真正生效的步骤链长什么样。</p>
 *
 * <p>因此它非常适合作为 `confirmStrategy` 的直接返回值，
 * 让调用方立即知道“这次确认之后，系统认定的最终执行方案是什么”。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStrategyConfirmVo {

    /**
     * 文档 id。
     *
     * <p>表示这次确认结果属于哪一份文档。</p>
     */
    private Long documentId;

    /**
     * 最终生效方案 id。
     *
     * <p>注意这里不一定等于请求里的 `basePlanId`：</p>
     * <p>1. 如果用户没有真正改动方案，通常就是原方案 id。</p>
     * <p>2. 如果用户调整了顺序或策略集合，后端可能新建一版方案，这里返回新的 planId。</p>
     */
    private Long planId;

    /**
     * 最终生效方案版本号。
     *
     * <p>用于说明最终确认的是该文档的第几版方案。</p>
     */
    private Integer planVersion;

    /**
     * 当前策略状态码。
     *
     * <p>在 `confirmStrategy` 正常成功时，这里应当是 CONFIRMED 对应的状态码。</p>
     */
    private Integer strategyStatus;

    /**
     * 当前策略状态名称。
     *
     * <p>这是给调用方直接展示或日志记录用的文案字段。</p>
     */
    private String strategyStatusName;

    /**
     * 服务端是否对用户提交的策略链做了规范化处理。
     *
     * <p>典型场景包括：</p>
     * <p>1. 过滤非法策略类型。</p>
     * <p>2. 去掉重复策略。</p>
     * <p>3. 对输入顺序进行标准化。</p>
     *
     * <p>如果为 `true`，说明后端最终认定的生效链路和用户原始提交并不完全一样。</p>
     */
    private Boolean normalized;

    /**
     * 最终生效的策略步骤列表。
     *
     * <p>这里返回的是“确认完成后真正会参与后续索引构建的步骤链”，
     * 而不是原始请求 steps，也不是数据库里所有历史方案步骤。</p>
     */
    private List<DocumentStrategyStepVo> steps;
}
