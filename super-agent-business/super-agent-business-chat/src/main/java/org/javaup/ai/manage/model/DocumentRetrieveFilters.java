package org.javaup.ai.manage.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档检索过滤提示。
 *
 * <p>这组字段不直接描述“最终如何排序”，
 * 只表达“本轮检索时有哪些可利用的元数据线索”。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRetrieveFilters {

    /**
     * 文档名称提示词。
     */
    @Builder.Default
    private List<String> documentNameHints = new ArrayList<>();

    /**
     * 业务分类提示词。
     */
    @Builder.Default
    private List<String> businessCategoryHints = new ArrayList<>();

    /**
     * 文档标签提示词。
     */
    @Builder.Default
    private List<String> documentTagHints = new ArrayList<>();

    /**
     * 章节路径提示词。
     *
     * <p>这里主要承接显式章节/附录/条款类定位线索，
     * 适合直接参与 section_path 过滤。</p>
     */
    @Builder.Default
    private List<String> sectionPathHints = new ArrayList<>();

    /**
     * 年份提示词。
     */
    @Builder.Default
    private List<String> yearHints = new ArrayList<>();

    public boolean isEmpty() {
        return documentNameHints.isEmpty()
            && businessCategoryHints.isEmpty()
            && documentTagHints.isEmpty()
            && sectionPathHints.isEmpty()
            && yearHints.isEmpty();
    }
}
