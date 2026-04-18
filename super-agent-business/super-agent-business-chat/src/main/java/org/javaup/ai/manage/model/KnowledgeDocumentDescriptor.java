package org.javaup.ai.manage.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 可参与知识检索的文档描述对象
 * @author: 阿星不是程序员
 **/
/**
 * 可参与知识检索的文档描述对象。
 *
 * <p>聊天侧不应该直接面向数据库实体做复杂判断，
 * 而应该先拿到一个裁剪后的“检索目录对象”，
 * 用来驱动文档选择下拉框和当前文档问答链路。</p>
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
