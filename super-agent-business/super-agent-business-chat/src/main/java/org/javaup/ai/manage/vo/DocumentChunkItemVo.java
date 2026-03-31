package org.javaup.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档 chunk 单条出参。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunkItemVo {

    private Long chunkId;

    private Integer chunkNo;

    private String sectionPath;

    private String pageNo;

    private Integer sourceType;

    private String sourceTypeName;

    private Integer charCount;

    private Integer tokenCount;

    private Integer vectorStatus;

    private String vectorStatusName;

    private String chunkText;
}
