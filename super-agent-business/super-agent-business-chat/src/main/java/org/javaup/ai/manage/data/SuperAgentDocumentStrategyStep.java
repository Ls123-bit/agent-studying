package org.javaup.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 数据实体
 * @author: 阿星不是程序员
 **/
/**
 * 文档策略步骤实体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_document_strategy_step")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentStrategyStep extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 所属方案 id。
     */
    private Long planId;

    /**
     * 文档 id。
     */
    private Long documentId;

    /**
     * 执行顺序。
     */
    private Integer stepNo;

    /**
     * 流水线类型。
     */
    private String pipelineType;

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
     * 执行状态。
     */
    private Integer executeStatus;

    /**
     * 本步骤推荐原因。
     */
    private String recommendReason;
}
