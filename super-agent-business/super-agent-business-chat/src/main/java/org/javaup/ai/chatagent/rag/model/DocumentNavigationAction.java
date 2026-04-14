package org.javaup.ai.chatagent.rag.model;

/**
 * 文档导航动作。
 *
 * <p>它表达的是“当前轮相对上一轮，在文档结构树上要做什么导航动作”。</p>
 */
public enum DocumentNavigationAction {
    TOPIC_CONTINUE,
    TOPIC_SWITCH,
    FRESH_TOPIC,
    SIBLING_SECTION_SWITCH,
    CHILD_SECTION_DESCEND,
    ANCESTOR_SECTION_RETURN,
    ITEM_REFERENCE,
    SECTION_ADJACENCY_LOOKUP
}
