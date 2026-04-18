package org.javaup.ai.manage.model.es;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: Elasticsearch 关键词索引文档
 * @author: 阿星不是程序员
 **/
/**
 * Elasticsearch 关键词索引文档。
 *
 * <p>这里保存的是“面向全文检索”的结构，
 * 不是数据库实体，也不是前端视图对象。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentKeywordIndexRecord {

    /**
     * 与业务 chunk 主键保持一致的 ES 文档 id。
     */
    private String chunkId;

    /**
     * 所属文档 id。
     */
    private Long documentId;

    /**
     * 所属索引任务 id。
     */
    private Long taskId;

    /**
     * 所属父块 id。
     */
    private Long parentBlockId;

    /**
     * chunk 序号。
     */
    private Integer chunkNo;

    /**
     * 文档名称。
     */
    private String documentName;

    /**
     * 章节路径。
     */
    private String sectionPath;

    /**
     * 关联的结构节点 id。
     */
    private Long structureNodeId;

    /**
     * 关联的结构节点类型。
     */
    private Integer structureNodeType;

    /**
     * 结构节点稳定路径。
     */
    private String canonicalPath;

    /**
     * 列表/步骤项序号。
     */
    private Integer itemIndex;

    /**
     * 业务知识域编码。
     */
    private String knowledgeScopeCode;

    /**
     * 业务知识域名称。
     */
    private String knowledgeScopeName;

    /**
     * 业务分类。
     */
    private String businessCategory;

    /**
     * 标签列表。
     */
    @Builder.Default
    private List<String> documentTags = new ArrayList<>();

    /**
     * chunk 正文。
     */
    private String chunkText;
}
