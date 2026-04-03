package org.javaup.ai.manage.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 可参与知识检索的文档描述对象。
 *
 * <p>聊天侧做知识域收缩、歧义澄清和检索规划时，
 * 不应该直接面向数据库实体做复杂判断，
 * 而应该先拿到一个裁剪后的“检索目录对象”。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentDescriptor {

    /**
     * 文档主键。
     */
    private Long documentId;

    /**
     * 文档名称。
     */
    private String documentName;

    /**
     * 当前生效索引任务。
     *
     * <p>检索时必须同时限定 documentId 和 lastIndexTaskId，
     * 避免命中同一文档历史旧版本的向量数据。</p>
     */
    private Long lastIndexTaskId;

    /**
     * 知识域编码。
     */
    private String knowledgeScopeCode;

    /**
     * 知识域名称。
     */
    private String knowledgeScopeName;

    /**
     * 业务分类。
     */
    private String businessCategory;

    /**
     * 标签快照。
     */
    private String documentTags;
}
