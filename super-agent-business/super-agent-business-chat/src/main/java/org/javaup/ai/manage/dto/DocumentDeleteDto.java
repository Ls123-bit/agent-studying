package org.javaup.ai.manage.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 删除文档入参。
 *
 * <p>前端按字符串形式传 documentId，
 * 服务端在应用层统一完成格式转换。</p>
 */
@Data
public class DocumentDeleteDto {

    /**
     * 文档 id。
     */
    @NotBlank(message = "文档id不能为空")
    private String documentId;
}
