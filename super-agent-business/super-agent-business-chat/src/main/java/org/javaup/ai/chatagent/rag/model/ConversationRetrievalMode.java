package org.javaup.ai.chatagent.rag.model;

/**
 * 当前问题更适合采用的检索规划模式。
 */
public enum ConversationRetrievalMode {
    /**
     * 单问题直接检索。
     */
    DIRECT_QUERY,

    /**
     * 优先定位某个主题下的特定信息切面或章节。
     */
    SECTION_FOCUSED,

    /**
     * 需要真正拆成多个分析子问题分别检索。
     */
    ANALYTIC_DECOMPOSITION,

    /**
     * 无法稳定判断时的兜底状态。
     */
    UNKNOWN
}
