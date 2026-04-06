package org.javaup.ai.manage.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文档检索请求。
 *
 * <p>这个对象只描述“要查什么”，不描述“最后怎么回答”。
 * 这样文档管理侧可以专注提供通用检索能力，
 * 聊天侧再根据拿到的证据决定如何组 Prompt、如何流式回答。</p>
 */
@Data
@NoArgsConstructor
public class DocumentRetrieveRequest {

    /**
     * 当前子问题原始文本。
     *
     * <p>这个字段保留用户当前这一问的原始问法，
     * 主要用于日志、过滤提示提取和调试观察。</p>
     */
    private String question;

    /**
     * 真正用于检索的查询文本。
     *
     * <p>它和 {@link #question} 的区别是：
     * - question 保留用户原始问法
     * - retrievalQuery 允许在短追问场景下拼入少量历史检索线索
     *
     * 这样可以同时做到：
     * 1. 不篡改用户原始问题
     * 2. 让向量检索和关键词检索真正吃到“增强后的查询”</p>
     */
    private String retrievalQuery;

    /**
     * 限定的文档主键。
     *
     * <p>当前教学版聊天链路固定为“当前文档问答”，
     * 因此这里不再接受多文档范围。</p>
     */
    private Long documentId;

    /**
     * 限定的索引任务主键。
     */
    private Long taskId;

    /**
     * 本次检索期望返回的候选数量。
     */
    private int topK;

    /**
     * 元数据过滤提示。
     */
    private DocumentRetrieveFilters filters;

    /**
     * 主查询之外的上下文提示。
     *
     * <p>例如短追问场景下继承来的系统名、模块名、关键词。
     * 这类信息不会直接污染主 query embedding，只在需要的通道中做轻量辅助。</p>
     */
    private List<String> queryContextHints;

    public DocumentRetrieveRequest(String question,
                                   String retrievalQuery,
                                   Long documentId,
                                   Long taskId,
                                   int topK) {
        this(question, retrievalQuery, documentId, taskId, topK, null, List.of());
    }

    public DocumentRetrieveRequest(String question,
                                   String retrievalQuery,
                                   Long documentId,
                                   Long taskId,
                                   int topK,
                                   DocumentRetrieveFilters filters) {
        this(question, retrievalQuery, documentId, taskId, topK, filters, List.of());
    }

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
        this.topK = topK;
        this.filters = filters;
        this.queryContextHints = queryContextHints;
    }
}
