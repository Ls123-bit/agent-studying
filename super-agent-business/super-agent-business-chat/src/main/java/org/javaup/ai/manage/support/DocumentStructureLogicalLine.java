package org.javaup.ai.manage.support;

/**
 * 文档结构解析前的逻辑行。
 *
 * <p>它位于“原始换行文本”和“结构信号”之间，
 * 用于承接行内步骤拆分、缩进保留等预处理结果。</p>
 */
public record DocumentStructureLogicalLine(
    int lineNo,
    int sourceLineNo,
    int segmentIndex,
    int indentLevel,
    String rawText,
    String normalizedText
) {
}
