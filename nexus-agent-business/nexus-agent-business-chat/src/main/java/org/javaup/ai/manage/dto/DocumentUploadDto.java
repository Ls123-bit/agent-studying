package org.javaup.ai.manage.dto;

import lombok.Data;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 数据传输对象
 * @author: 阿星不是程序员
 **/


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
