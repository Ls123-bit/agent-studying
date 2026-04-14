package org.javaup.ai.chatagent.rag.model;

/**
 * 对话执行模式。
 */
public enum ExecutionMode {
    /**
     * 只走结构图，不走文本检索。
     * 适合：章节列表、上一节/下一节、属于哪个章节。
     */
    GRAPH_ONLY,

    /**
     * 先走结构图定位，再读取目标节点正文或 parent block 证据。
     * 适合：第几步是什么、哪一步要求修改密码。
     */
    GRAPH_THEN_EVIDENCE,

    /**
     * 传统文本检索，由导航决策提供结构范围和证据策略。
     * 适合：跨章节比较、原因分析、综合说明。
     */
    RAG_CHAT,

    /**
     * 开放式 Agent 能力，联网搜索、闲聊等。
     */
    REACT_AGENT
}
