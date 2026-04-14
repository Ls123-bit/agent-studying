package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 证据约束策略。
 *
 * <p>它不是检索过滤条件，而是回答前必须满足的证据完整性约束。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentEvidencePolicy {

    /**
     * 是否必须命中目标结构节点或其明确范围。
     */
    private boolean targetStructureRequired;

    /**
     * 是否必须命中精确编号项。
     */
    private boolean exactItemRequired;

    /**
     * 是否必须包含相邻章节上下文。
     */
    private boolean siblingContextRequired;

    /**
     * 如果目标结构不存在，是否必须直接无证据。
     */
    private boolean noEvidenceWhenTargetMissing;
}
