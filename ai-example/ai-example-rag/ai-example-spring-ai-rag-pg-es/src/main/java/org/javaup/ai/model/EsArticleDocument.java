package org.javaup.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Elasticsearch 中保存的文档结构。
 * <p>
 * 这个类只服务于 ES 这一条检索链路：
 * - 写入时，作为倒排索引的原始文档结构
 * - 查询时，作为 SearchResponse 的强类型结果对象
 * <p>
 * 这样比直接用 Map 更清晰，也能避免一堆原始类型告警。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EsArticleDocument {

    /**
     * 和 PGVector 中 Document.id 完全一致的唯一标识。
     * 注意这里在 ES 中的字段名是 chunk_id，所以用 @JsonProperty 做映射。
     */
    @JsonProperty("chunk_id")
    private String chunkId;

    /**
     * 文档标题。
     */
    private String title;

    /**
     * 文档正文内容。
     */
    private String content;

    /**
     * 文档分类，比如 database、container、framework。
     */
    private String category;

    /**
     * 标签列表，适合存一些版本号、组件名、专题关键词。
     */
    private List<String> tags;
}
