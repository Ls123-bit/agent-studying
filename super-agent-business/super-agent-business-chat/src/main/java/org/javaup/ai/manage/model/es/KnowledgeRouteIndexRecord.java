package org.javaup.ai.manage.model.es;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 知识路由索引记录
 * @author: 阿星不是程序员
 **/
/**
 * 知识路由索引记录。
 *
 * <p>这套索引不是正文检索索引，而是“路由元数据索引”：
 * scope / topic / document 三类对象都会被整理成一份可检索快照，
 * 供 AUTO_DOCUMENT 在词法层做专有词增强和实体约束。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeRouteIndexRecord {

    /**
     * ES 文档 id，规则：scope:xxx / topic:xxx / document:123
     */
    private String routeId;

    /**
     * scope / topic / document
     */
    private String entityType;

    private String entityCode;

    private Long documentId;

    private String scopeCode;

    private String scopeName;

    private String topicCode;

    private String topicName;

    private String documentName;

    private String businessCategory;

    /**
     * 名称类文本，适合 phrase / exact lexical boost。
     */
    private String displayName;

    private String descriptionText;

    private String aliasesText;

    private String examplesText;

    private String summaryText;

    /**
     * 聚合后的路由文本，给 lexical multi match 使用。
     */
    private String routeText;

    /**
     * 专有词 / 型号词 / 协议词 / 缩写词。
     */
    @Builder.Default
    private List<String> entityTerms = new ArrayList<>();

    /**
     * 文档标签或核心主题等弱实体词。
     */
    @Builder.Default
    private List<String> tags = new ArrayList<>();
}
