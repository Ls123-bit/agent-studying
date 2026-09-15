package org.javaup.ai.chatagent.model.trace;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 轮次链路阶段定义
 * @author: 阿星不是程序员
 **/



/*
全链路可追踪/追踪阶段编码
 */
public enum ConversationTraceStageCode {

    MEMORY("MEMORY", "会话记忆", 10),//记忆加载
    INTENT("INTENT", "意图分析", 20),
    REWRITE("REWRITE", "问题改写", 30),//问题改写
    ROUTE("ROUTE", "路由判定", 40),//知识路由
    GRAPH_QUERY("GRAPH_QUERY", "结构图查询", 45),//图结构查询
    RAG_RETRIEVE("RAG_RETRIEVE", "RAG 检索", 50),//RAG 检索
    EVIDENCE_BUDGET("EVIDENCE_BUDGET", "证据评估与预算控制", 60),//证据预算控制
    ANSWER_GENERATE("ANSWER_GENERATE", "回答生成", 70),//回答生成
    REACT_AGENT("REACT_AGENT", "ReAct Agent", 75),//ReAct Agent执行
    RECOMMENDATION("RECOMMENDATION", "推荐问题", 80),//推荐问题生成
    FINALIZE("FINALIZE", "收尾归档", 90);//收尾处理

    private final String code;
    private final String label;
    private final int order;

    ConversationTraceStageCode(String code, String label, int order) {
        this.code = code;
        this.label = label;
        this.order = order;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public int getOrder() {
        return order;
    }
}
