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
@AllArgsConstructor
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
}
