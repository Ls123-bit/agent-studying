package org.javaup.ai.manage.service;

import org.javaup.ai.manage.model.route.KnowledgeRouteDecision;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
/*
知识路由（三层漏斗）的核心接口
 */
public interface KnowledgeRouteService {

    KnowledgeRouteDecision route(String question, String rewriteQuestion);//路由主入口，返回决策结果

    void recordShadowRoute(String conversationId,//影子路由：用户手动选了文档时，后台静默跑一次路由做对比
                           long exchangeId,
                           Long selectedDocumentId,
                           String question,
                           String rewriteQuestion);

    void recordAutoRoute(String conversationId,//记录自动路由的结果（用于质量追踪）
                         long exchangeId,
                         String question,
                         String rewriteQuestion,
                         KnowledgeRouteDecision decision);
}
