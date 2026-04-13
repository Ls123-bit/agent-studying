package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话结构锚点。
 *
 * <p>它表达的是“当前问题最可能落在哪个文档结构范围内”。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationStructureAnchor {

    /**
     * 根章节编码。
     */
    private String rootSectionCode;

    /**
     * 根章节标题。
     */
    private String rootSectionTitle;

    /**
     * 当前目标章节提示。
     */
    private String targetSectionHint;

    /**
     * 结构节点主键。
     */
    private Long structureNodeId;

    /**
     * 结构节点稳定路径。
     */
    private String canonicalPath;

    /**
     * 当前 scope 模式。
     *
     * <p>NONE / SOFT / HARD</p>
     */
    private String scopeMode;

    public boolean isEmpty() {
        return (rootSectionCode == null || rootSectionCode.isBlank())
            && (rootSectionTitle == null || rootSectionTitle.isBlank())
            && (targetSectionHint == null || targetSectionHint.isBlank())
            && structureNodeId == null
            && (canonicalPath == null || canonicalPath.isBlank());
    }
}
