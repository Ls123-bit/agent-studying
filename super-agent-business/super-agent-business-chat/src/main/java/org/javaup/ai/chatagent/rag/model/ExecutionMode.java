package org.javaup.ai.chatagent.rag.model;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 对话执行模式
 * @author: 阿星不是程序员
 **/
/**
 * 对话执行模式。
 */
public enum ExecutionMode {
    /**
     * 当前问题适合走“结构图直接回答”的模式。
     */
    GRAPH_ONLY,

    /**
     * 当前问题适合走“结构图定位后直接读取章节/item 证据”的模式。
     */
    GRAPH_THEN_EVIDENCE,

    /**
     * 当前问题适合走“先检索证据、再严格基于证据回答”的知识问答模式。
     */
    RETRIEVAL,

    /**
     * 当前问题更适合走开放式 Agent 能力，例如联网搜索、普通闲聊、实时信息处理等。
     */
    REACT_AGENT,

    /**
     * 当前候选文档存在明显歧义，优先返回澄清问题给用户确认范围。
     */
    CLARIFICATION,

    /**
     * 兼容旧调试数据保留的历史模式值。
     */
    @Deprecated
    RAG_CHAT
}
