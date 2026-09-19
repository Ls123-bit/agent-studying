package org.javaup.ai.manage.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;


@Data
public class DocumentDeleteDto {

    @NotBlank(message = "文档id不能为空")
    private String documentId;
}
