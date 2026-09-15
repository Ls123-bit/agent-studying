package org.javaup.ai.manage.model.route;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 模型对象
 * @author: 阿星不是程序员
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
/*
路由决策结果
 */
public class KnowledgeRouteDecision {

    private List<ScopeRouteCandidate> scopes = new ArrayList<>();//Scope 候选列表（带分数）

    private List<TopicRouteCandidate> topics = new ArrayList<>();//Topic 候选列表（带分数）

    private List<DocumentRouteCandidate> documents = new ArrayList<>();//Document 候选列表（带分数）

    private BigDecimal confidence = BigDecimal.ZERO;//路由置信度

    private String routeStatus = "SUCCESS";//状态：SUCCESS / LOW_CONFIDENCE / FAILED

    private String reason = "";//路由原因说明

    public DocumentRouteCandidate topDocument() {//返回排名第一的文档候选
        return documents == null || documents.isEmpty() ? null : documents.get(0);
    }
}
