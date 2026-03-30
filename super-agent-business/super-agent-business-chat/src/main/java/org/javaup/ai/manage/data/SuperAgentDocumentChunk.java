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
 * 文档切块实体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_document_chunk")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentChunk extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 文档 id。
     */
    private Long documentId;

    /**
     * 索引任务 id。
     */
    private Long taskId;

    /**
     * 策略方案 id。
     */
    private Long planId;

    /**
     * 切块序号。
     */
    private Integer chunkNo;

    /**
     * 内容来源。
     */
    private Integer sourceType;

    /**
     * 章节路径。
     */
    private String sectionPath;

    /**
     * 页码范围。
     */
    private String pageNo;

    /**
     * 切块内容。
     */
    private String chunkText;

    /**
     * 字符数。
     */
    private Integer charCount;

    /**
     * token 数。
     */
    private Integer tokenCount;

    /**
     * 向量状态。
     */
    private Integer vectorStatus;

    /**
     * 向量库类型。
     */
    private Integer vectorStoreType;

    /**
     * 向量主键。
     */
    private String vectorId;
}
