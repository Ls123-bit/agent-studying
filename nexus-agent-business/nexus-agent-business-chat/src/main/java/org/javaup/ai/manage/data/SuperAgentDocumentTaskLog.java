package org.javaup.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;


@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("nexus_agent_document_task_log")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentTaskLog extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long taskId;

    private Long documentId;

    private Integer stageType;

    private Integer eventType;

    private Integer logLevel;

    private Integer operatorType;

    private Long operatorId;

    private String content;

    private String detailJson;
}
