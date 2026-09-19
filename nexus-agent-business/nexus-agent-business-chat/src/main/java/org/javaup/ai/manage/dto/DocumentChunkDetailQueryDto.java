package org.javaup.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;


@Data
public class DocumentChunkDetailQueryDto {

    @NotNull(message = "文档id不能为空")
    private Long documentId;

    @NotNull(message = "chunkId不能为空")
    private Long chunkId;

    private Long taskId;
}
