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
@TableName("nexus_agent_document_chunk")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentChunk extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long documentId;

    private Long taskId;

    private Long planId;

    private Long parentBlockId;

    private Integer chunkNo;

    private Integer sourceType;

    private String sectionPath;

    private Long structureNodeId;

    private Integer structureNodeType;

    private String canonicalPath;

    private Integer itemIndex;

    private String chunkText;

    private Integer charCount;

    private Integer tokenCount;

    private Integer vectorStatus;

    private Integer vectorStoreType;

    private String vectorId;
}
