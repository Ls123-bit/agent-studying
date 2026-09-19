package org.javaup.ai.manage.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;


@Data
@NoArgsConstructor
public class DocumentRetrieveRequest {

    private String question;

    private String retrievalQuery;

    private Long documentId;

    private Long taskId;

    private List<Long> documentIds;

    private List<Long> taskIds;

    private int topK;

    private DocumentRetrieveFilters filters;

    private List<String> queryContextHints;

    public DocumentRetrieveRequest(String question,
                                   String retrievalQuery,
                                   Long documentId,
                                   Long taskId,
                                   int topK,
                                   DocumentRetrieveFilters filters,
                                   List<String> queryContextHints) {
        this.question = question;
        this.retrievalQuery = retrievalQuery;
        this.documentId = documentId;
        this.taskId = taskId;
        this.documentIds = documentId == null ? List.of() : List.of(documentId);
        this.taskIds = taskId == null ? List.of() : List.of(taskId);
        this.topK = topK;
        this.filters = filters;
        this.queryContextHints = queryContextHints;
    }





    /*
    下面这两个方法是 DocumentRetrieveRequest 的便捷方法
    这样可以同时支持单文档检索和多文档检索
     */
    public List<Long> resolvedDocumentIds() {
        // 优先使用批量 documentIds，如果为空则回退到单个 documentId
        return documentIds != null && !documentIds.isEmpty()
            ? documentIds
            : (documentId == null ? List.of() : List.of(documentId));
    }

    public List<Long> resolvedTaskIds() {
        // 优先使用批量 taskIds，如果为空则回退到单个 taskId
        return taskIds != null && !taskIds.isEmpty()
            ? taskIds
            : (taskId == null ? List.of() : List.of(taskId));
    }
}
