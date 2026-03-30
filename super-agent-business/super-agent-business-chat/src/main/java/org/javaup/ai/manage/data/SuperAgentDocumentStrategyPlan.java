package org.javaup.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;

import java.util.Date;

/**
 * 文档策略方案实体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_document_strategy_plan")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentStrategyPlan extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 文档 id。
     */
    private Long documentId;

    /**
     * 方案版本号。
     */
    private Integer planVersion;

    /**
     * 方案来源。
     */
    private Integer planSource;

    /**
     * 方案状态。
     */
    private Integer planStatus;

    /**
     * 策略数量。
     */
    private Integer strategyCount;

    /**
     * 策略快照。
     */
    private String strategySnapshot;

    /**
     * 系统推荐原因。
     */
    private String recommendReason;

    /**
     * 用户调整说明。
     */
    private String adjustNote;

    /**
     * 确认人 id。
     */
    private Long confirmUserId;

    /**
     * 确认时间。
     */
    private Date confirmTime;
}
