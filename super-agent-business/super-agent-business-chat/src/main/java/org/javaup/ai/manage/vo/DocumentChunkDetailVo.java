package org.javaup.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个 chunk 详情出参。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunkDetailVo {

    private Long documentId;

    private Long taskId;

    private Long planId;

    private DocumentChunkItemVo chunk;

    private DocumentParentBlockItemVo parentBlock;

    private List<DocumentChunkItemVo> siblingChunks;
}
