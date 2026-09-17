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
/*
执行器事件桥接
各个执行器（RAG、Agent、澄清等）在执行过程中需要向前端发送"思考中"或"状态变更"事件。ExecutorEventSupport 提供了统一的桥接方法，把"格式化 + 推送"封装成一步调用：
 */
// ExecutorEventSupport.java —— 执行器事件桥接
public final class ExecutorEventSupport {

    private ExecutorEventSupport() {
    }

    // 发布思考步骤事件
    public static void publishThinking(TaskInfo taskInfo, StreamEventWriter writer, String content) {
        if (taskInfo == null || writer == null || StrUtil.isBlank(content)) {
            return;
        }
        // 先记录到 thinkingSteps 集合，收尾时会一起落库
        taskInfo.thinkingSteps().add(content);
        // 再通过 SinkEmitHelper 推送给前端
        SinkEmitHelper.emitNext(taskInfo.sink(), writer.thinking(content, taskInfo.eventMetadata()));
    }

    // 发布状态通知事件
    public static void publishStatus(TaskInfo taskInfo, StreamEventWriter writer, String content) {
        if (taskInfo == null || writer == null || StrUtil.isBlank(content)) {
            return;
        }
        SinkEmitHelper.emitNext(taskInfo.sink(), writer.status(content, taskInfo.eventMetadata()));
    }
}
/*
注意 publishThinking 和 publishStatus 的区别：
thinking 事件会同时写入 thinkingSteps 集合（最终落库），
而 status 事件只推送不记录。这是因为思考步骤是回答过程的一部分，需要持久化；
而状态通知（比如"已停止"）是临时性的，不需要保存。
 */