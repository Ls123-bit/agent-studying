package org.javaup.ai.manage.support;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/
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
