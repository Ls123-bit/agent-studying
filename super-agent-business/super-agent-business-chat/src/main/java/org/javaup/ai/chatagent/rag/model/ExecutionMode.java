package org.javaup.ai.chatagent.rag.model;

/**
 * 对话执行模式。
 */
public enum ExecutionMode {

    /**
     * 当前问题存在高优先级歧义，先追问用户而不是直接回答。
     */
    CLARIFY,

    /**
     * 当前问题适合走“先检索证据、再严格基于证据回答”的知识问答模式。
     */
    RAG_CHAT,

    /**
     * 当前问题更适合走开放式 Agent 能力，例如联网搜索、普通闲聊、实时信息处理等。
     */
    REACT_AGENT
}
