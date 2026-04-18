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
 * 文档任务日志实体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_document_task_log")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentTaskLog extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 任务 id。
     */
    private Long taskId;

    /**
     * 文档 id。
     */
    private Long documentId;

    /**
     * 阶段类型。
     */
    private Integer stageType;

    /**
     * 事件类型。
     */
    private Integer eventType;

    /**
     * 日志级别。
     */
    private Integer logLevel;

    /**
     * 操作人类型。
     */
    private Integer operatorType;

    /**
     * 操作人 id。
     */
    private Long operatorId;

    /**
     * 日志内容。
     */
    private String content;

    /**
     * 日志详情 JSON。
     */
    private String detailJson;
}
