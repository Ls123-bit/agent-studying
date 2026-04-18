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
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 数据实体
 * @author: 阿星不是程序员
 **/
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
