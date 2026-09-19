package org.javaup.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/*
返回给前端的DocumentUploadVO包含
只返回了文档 ID、任务 ID、文档名称和三个状态码，
不会暴露 MinIO 的存储路径、bucket 名称这些内部细节。前端拿到 documentId 和 taskId 之后，就可以用它们去轮询文档的处理进度了。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentUploadVo {

    private Long documentId;//文档id

    private Long taskId;//任务id

    private String documentName;//文档名称

    private Integer parseStatus;

    private Integer strategyStatus;

    private Integer indexStatus;
}
