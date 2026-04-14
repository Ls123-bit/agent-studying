package org.javaup.ai.manage.support;

/**
 * 结构信号类型。
 *
 * <p>它表达的是“当前这一行更像什么结构角色”，
 * 还不是最终的树节点类型。</p>
 */
public enum DocumentStructureSignalKind {
    DOCUMENT_TITLE,
    HEADING,
    HEADING_CANDIDATE,
    STEP_ITEM,
    LIST_ITEM,
    TABLE_ROW,
    QUOTE,
    BODY,
    BLANK,
    NOISE
}
