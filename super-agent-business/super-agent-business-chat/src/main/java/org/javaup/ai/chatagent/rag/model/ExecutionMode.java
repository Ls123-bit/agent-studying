package org.javaup.ai.chatagent.rag.model;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 对话执行模式
 * @author: 阿星不是程序员
 **/

public enum ExecutionMode {

    GRAPH_ONLY,

    GRAPH_THEN_EVIDENCE,

    RETRIEVAL,

    REACT_AGENT,

    CLARIFICATION,

    @Deprecated
    RAG_CHAT
}
