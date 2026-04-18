package org.javaup.ai.manage.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 数据传输对象
 * @author: 阿星不是程序员
 **/
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
