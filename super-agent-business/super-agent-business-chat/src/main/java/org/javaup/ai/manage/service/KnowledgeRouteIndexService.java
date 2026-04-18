package org.javaup.ai.manage.service;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
/**
 * 知识路由 ES 索引服务。
 *
 * <p>职责：</p>
 * <p>1. 把 scope / topic / document 路由元数据同步到 ES。</p>
 * <p>2. 提供 lexical 通道检索，服务于 AUTO_DOCUMENT 的专有词增强。</p>
 */
public interface KnowledgeRouteIndexService {

    /**
     * 按需刷新整套路由索引快照。
     */
    void refreshIfNeeded();

    /**
     * 按实体类型执行 lexical 路由搜索。
     */
    List<RouteLexicalHit> search(String routingText, String entityType, int size);

    /**
     * 删除指定文档在知识路由索引中的 document 路由快照。
     */
    void deleteDocumentRoute(Long documentId);

    record RouteLexicalHit(
        String routeId,
        String entityCode,
        String entityType,
        Long documentId,
        String scopeCode,
        String topicCode,
        String documentName,
        double score
    ) {
    }
}
