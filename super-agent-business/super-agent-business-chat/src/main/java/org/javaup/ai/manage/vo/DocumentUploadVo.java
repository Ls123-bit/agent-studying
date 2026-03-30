package org.javaup.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上传文档出参。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentUploadVo {

    private Long documentId;

    private Long taskId;

    private String documentName;

    private Integer parseStatus;

    private Integer strategyStatus;

    private Integer indexStatus;
}
