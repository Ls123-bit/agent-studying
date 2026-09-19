package org.javaup.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;



/*
请求参数很简洁，三个字段：

 */
@Data
public class DocumentIndexBuildDto {

    @NotNull(message = "文档id不能为空")
    private Long documentId;//要构建索引的文档 ID

    @NotNull(message = "方案id不能为空")
    private Long planId;//当前生效的策略方案 ID

    private Long operatorId;//操作人 ID，可以为空（为空表示系统自动触发）
}
