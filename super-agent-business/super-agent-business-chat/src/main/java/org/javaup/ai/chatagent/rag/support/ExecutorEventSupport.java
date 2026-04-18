package org.javaup.ai.chatagent.rag.support;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.SinkEmitHelper;
import org.javaup.ai.chatagent.support.StreamEventWriter;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/
/**
 * 执行器通用事件辅助类。
 *
 * <p>三个执行器都需要写 thinking/status 事件，
 * 如果每个执行器都各自复制一份 sink 写入逻辑，后面会很难统一维护，
 * 因此这里抽一个极薄的辅助类承接公共动作。</p>
 */
public final class ExecutorEventSupport {

    private ExecutorEventSupport() {
    }

    /**
     * 发布 thinking 事件，同时把文本沉淀到任务运行态里。
     */
    public static void publishThinking(TaskInfo taskInfo, StreamEventWriter writer, String content) {
        if (taskInfo == null || writer == null || StrUtil.isBlank(content)) {
            return;
        }
        taskInfo.thinkingSteps().add(content);
        SinkEmitHelper.emitNext(taskInfo.sink(), writer.thinking(content, taskInfo.eventMetadata()));
    }

    /**
     * 发布 status 事件。
     */
    public static void publishStatus(TaskInfo taskInfo, StreamEventWriter writer, String content) {
        if (taskInfo == null || writer == null || StrUtil.isBlank(content)) {
            return;
        }
        SinkEmitHelper.emitNext(taskInfo.sink(), writer.status(content, taskInfo.eventMetadata()));
    }
}
