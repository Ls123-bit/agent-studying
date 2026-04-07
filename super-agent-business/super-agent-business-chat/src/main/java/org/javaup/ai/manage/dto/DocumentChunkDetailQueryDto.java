package org.javaup.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 查询单个 chunk 详情入参。
 */
@Data
public class DocumentChunkDetailQueryDto {

    /**
     * 文档 id。
     */
    @NotNull(message = "文档id不能为空")
    private Long documentId;

    /**
     * chunk id。
     */
    @NotNull(message = "chunkId不能为空")
    private Long chunkId;

    /**
     * 构建任务 id。
     *
     * <p>可选；不传时仍按文档当前最适合展示的任务版本解析。</p>
     */
    private Long taskId;
}
