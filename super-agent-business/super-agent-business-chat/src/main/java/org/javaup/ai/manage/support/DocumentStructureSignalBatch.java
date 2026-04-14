package org.javaup.ai.manage.support;

import java.util.List;

/**
 * 结构信号批次结果。
 *
 * <p>除了行级信号本身，还保留送给 LLM 判歧时使用的逻辑行上下文。</p>
 */
public record DocumentStructureSignalBatch(
    List<String> contextLines,
    List<DocumentStructureSignal> signals
) {
}
