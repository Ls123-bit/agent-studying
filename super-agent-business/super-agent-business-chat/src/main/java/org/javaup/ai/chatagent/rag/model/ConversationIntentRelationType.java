package org.javaup.ai.chatagent.rag.model;

/**
 * 当前问题和上文之间的关系类型。
 */
public enum ConversationIntentRelationType {
    /**
     * 继续承接当前主题。
     */
    FOLLOW_UP,

    /**
     * 在承接对话的同时切换到了新的明确主题。
     */
    TOPIC_SWITCH,

    /**
     * 一条完整、独立的新问题。
     */
    FRESH_TOPIC,

    /**
     * 无法稳定判断。
     */
    UNKNOWN
}
