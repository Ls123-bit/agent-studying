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
 * 文档异步任务实体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_document_task")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentTask extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 文档 id。
     */
    private Long documentId;

    /**
     * 关联方案 id。
     */
    private Long planId;

    /**
     * 任务类型。
     */
    private Integer taskType;

    /**
     * 任务状态。
     */
    private Integer taskStatus;

    /**
     * 当前阶段。
     */
    private Integer currentStage;

    /**
     * 触发来源。
     */
    private Integer triggerSource;

    /**
     * 任务执行时策略快照。
     */
    private String strategySnapshot;

    /**
     * 重试次数。
     */
    private Integer retryCount;

    /**
     * 开始时间。
     */
    private Date startTime;

    /**
     * 结束时间。
     */
    private Date finishTime;

    /**
     * 耗时毫秒。
     */
    private Long costMillis;

    /**
     * 错误码。
     */
    private String errorCode;

    /**
     * 错误信息。
     */
    private String errorMsg;

    /**
     * 扩展信息 JSON。
     */
    private String extJson;
}
