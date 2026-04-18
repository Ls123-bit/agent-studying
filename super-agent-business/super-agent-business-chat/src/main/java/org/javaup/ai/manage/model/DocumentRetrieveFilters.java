package org.javaup.ai.manage.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 文档检索过滤提示
 * @author: 阿星不是程序员
 **/
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
     * 结构节点稳定路径提示。
     *
     * <p>这一层主要表达“已经定位到某个结构子树”，
     * 适合在检索层做 canonicalPath 前缀过滤。</p>
     */
    @Builder.Default
    private List<String> canonicalPathHints = new ArrayList<>();

    /**
     * 结构节点主键提示。
     *
     * <p>适用于“已经明确锁定到某个步骤/列表项节点”的场景，
     * 可以直接作为更强的硬过滤条件。</p>
     */
    @Builder.Default
    private List<Long> structureNodeIdHints = new ArrayList<>();

    /**
     * 列表项/步骤项序号提示。
     *
     * <p>例如“第四步”“第 2 条”这类显式编号引用。</p>
     */
    @Builder.Default
    private List<Integer> itemIndexHints = new ArrayList<>();

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
            && canonicalPathHints.isEmpty()
            && structureNodeIdHints.isEmpty()
            && itemIndexHints.isEmpty()
            && yearHints.isEmpty();
    }
}
