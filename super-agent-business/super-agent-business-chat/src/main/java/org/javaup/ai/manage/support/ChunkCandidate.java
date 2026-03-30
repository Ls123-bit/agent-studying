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
     * 页码信息。
     */
    private String pageNo;

    /**
     * 切块内容。
     */
    private String text;

    /**
     * 内容来源。
     */
    private Integer sourceType;
}
