package org.javaup.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档问答命中片段出参。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentQuestionReferenceVo {

    /**
     * chunk 主键。
     */
    private Long chunkId;

    /**
     * 文档 id。
     */
    private Long documentId;

    /**
     * 文档名称。
     */
    private String documentName;

    /**
     * 当前命中的索引任务 id。
     */
    private Long taskId;

    /**
     * chunk 序号。
     */
    private Integer chunkNo;

    /**
     * 章节路径。
     */
    private String sectionPath;

    /**
     * 页码信息。
     */
    private String pageNo;

    /**
     * 命中片段内容。
     */
    private String chunkText;

    /**
     * 相似度得分。
     */
    private Double similarityScore;
}
