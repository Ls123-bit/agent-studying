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
     * 当前子问题文本。
     */
    private String question;

    /**
     * 限定的文档主键列表。
     */
    private List<Long> documentIdList;

    /**
     * 限定的索引任务列表。
     */
    private List<Long> taskIdList;

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
                                   List<Long> documentIdList,
                                   List<Long> taskIdList,
                                   int topK) {
        this(question, documentIdList, taskIdList, topK, null, List.of());
    }

    public DocumentRetrieveRequest(String question,
                                   List<Long> documentIdList,
                                   List<Long> taskIdList,
                                   int topK,
                                   DocumentRetrieveFilters filters) {
        this(question, documentIdList, taskIdList, topK, filters, List.of());
    }

    public DocumentRetrieveRequest(String question,
                                   List<Long> documentIdList,
                                   List<Long> taskIdList,
                                   int topK,
                                   DocumentRetrieveFilters filters,
                                   List<String> queryContextHints) {
        this.question = question;
        this.documentIdList = documentIdList;
        this.taskIdList = taskIdList;
        this.topK = topK;
        this.filters = filters;
        this.queryContextHints = queryContextHints;
    }
}
