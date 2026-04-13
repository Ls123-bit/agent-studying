package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 内部切块候选对象。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkCandidate {

    /**
     * 章节路径。
     */
    private String sectionPath;

    /**
     * 关联的结构节点 id。
     */
    private Long structureNodeId;

    /**
     * 关联的结构节点类型。
     */
    private Integer structureNodeType;

    /**
     * 结构节点稳定路径。
     */
    private String canonicalPath;

    /**
     * 列表/步骤项序号。
     */
    private Integer itemIndex;

    /**
     * 切块内容。
     */
    private String text;

    /**
     * 内容来源。
     */
    private Integer sourceType;

    public ChunkCandidate(String sectionPath, String text, Integer sourceType) {
        this(sectionPath, null, null, "", null, text, sourceType);
    }
}
