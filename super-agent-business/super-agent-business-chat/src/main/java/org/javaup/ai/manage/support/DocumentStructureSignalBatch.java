package org.javaup.ai.manage.support;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/
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
