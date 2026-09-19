package org.javaup.ai.manage.dto;

import lombok.Data;



/*
是上传的时候可以选择的元信息，包含以下字段
 */
@Data
public class DocumentUploadDto {

    private String documentName;//文档展示名称（不传则用原始文件名）

    private String operatorId;//操作人 ID（不传则视为系统触发）

    private String knowledgeScopeCode;//知识范围编码

    private String knowledgeScopeName;//知识范围名称

    private String businessCategory;//业务分类

    private String documentTags;//文档标签
}
