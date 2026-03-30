package org.javaup.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文档问答出参。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentQuestionAskVo {

    /**
     * 用户问题。
     */
    private String question;

    /**
     * 最终使用的 topK。
     */
    private Integer topK;

    /**
     * 参与检索的文档数量。
     */
    private Integer documentCount;

    /**
     * 实际命中的片段数量。
     */
    private Integer hitCount;

    /**
     * 基于召回片段生成的最终回答。
     */
    private String answer;

    /**
     * 命中片段列表。
     */
    private List<DocumentQuestionReferenceVo> referenceList;
}
