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
（这个对象包含了三层路由结果）
 */
public class KnowledgeRouteDecision {

    private List<ScopeRouteCandidate> scopes = new ArrayList<>();//Scope 候选列表（带分数） 知识域候选列表  知识域候选列表

    private List<TopicRouteCandidate> topics = new ArrayList<>();//Topic 候选列表（带分数） 主题候选列表 第二层：中等粒度的主题分类

    private List<DocumentRouteCandidate> documents = new ArrayList<>();//Document 候选列表（带分数）文档候选列表 第三层：具体的文档

    private BigDecimal confidence = BigDecimal.ZERO;//路由置信度 0-1 之间的数值，表示路由结果的可信程度

    private String routeStatus = "SUCCESS";//状态：SUCCESS / LOW_CONFIDENCE / FAILED 路由状态

    private String reason = "";//路由原因说明 原因说明  解释为什么选择这些候选

    public DocumentRouteCandidate topDocument() {//返回排名第一的文档候选
        return documents == null || documents.isEmpty() ? null : documents.get(0);
    }
}

/*
三层路由的设计思路


知识范围路由采用"知识域 → 主题 → 文档"的逐层缩小策略：
1.知识域（Scope） - 最粗粒度，比如"Java 开发"、"前端技术"、"数据库"
2.主题（Topic） - 中等粒度，比如"Spring Boot 配置"、"React Hooks"、"MySQL 索引优化"
3.文档（Document） - 最细粒度，具体的文档，比如"Spring Boot 官方文档"、"React 最佳实践"
这种设计的好处是：先粗筛再细排，避免一次性对所有文档打分，提高效率。
 */