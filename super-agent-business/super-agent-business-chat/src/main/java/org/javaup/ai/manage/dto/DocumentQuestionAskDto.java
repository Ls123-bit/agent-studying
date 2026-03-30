package org.javaup.ai.manage.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 文档问答入参。
 *
 * <p>这一期问答能力聚焦在“基于已构建索引的文档做 RAG 检索问答”，
 * 因此入参只需要用户问题、检索范围文档和召回条数，不额外暴露复杂配置。</p>
 */
@Data
public class DocumentQuestionAskDto {

    /**
     * 用户问题。
     */
    @NotBlank(message = "问题不能为空")
    private String question;

    /**
     * 参与检索的文档 id 列表。
     */
    @NotEmpty(message = "文档id列表不能为空")
    private List<Long> documentIdList;

    /**
     * 召回条数。
     *
     * <p>如果不传，后端会使用默认值 5；这里额外限制最大值，避免一次把太多 chunk 送给模型。</p>
     */
    @Min(value = 1, message = "topK 不能小于1")
    @Max(value = 20, message = "topK 不能大于20")
    private Integer topK;
}
