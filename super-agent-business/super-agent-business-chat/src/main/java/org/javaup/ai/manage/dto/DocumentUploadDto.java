package org.javaup.ai.manage.dto;

import lombok.Data;

/**
 * 上传文档的扩展元数据入参。
 *
 * <p>上传接口现在改为 {@code multipart/form-data}，
 * 文件本体通过 {@code file} 分段上传，这个 DTO 只承载与文件一同提交的业务元信息。</p>
 */
@Data
public class DocumentUploadDto {

    /**
     * 业务文档名称。
     *
     * <p>如果不传，则默认使用上传文件的原始文件名。</p>
     */
    private String documentName;

    /**
     * 操作人 id。
     */
    private Long operatorId;
}
