package org.javaup.ai.manage.service;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author: 阿星不是程序员
 **/

public interface KnowledgeRouteIndexService {

    void refreshIfNeeded();

    List<RouteLexicalHit> search(String routingText, String entityType, int size);

    void deleteDocumentRoute(Long documentId);

    record RouteLexicalHit(//RouteLexicalHit 是一个 record，封装了一次词法命中的所有信息：
        String routeId,//路由记录 ID
        String entityCode,//实体编码（知识域/主题/文档的唯一标识）
        String entityType,//实体类型：scope / topic / document
        Long documentId,//文档 ID（仅 document 类型有值）
        String scopeCode,//所属知识域编码
        String topicCode,//所属主题编码
        String documentName,//文档名称
        double score//Elasticsearch 返回的相关性得分
    ) {
    }
}
