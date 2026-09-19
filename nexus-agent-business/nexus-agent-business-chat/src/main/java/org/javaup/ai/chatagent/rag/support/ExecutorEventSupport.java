package org.javaup.ai.chatagent.rag.support;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.SinkEmitHelper;
import org.javaup.ai.chatagent.support.StreamEventWriter;

/*
执行器事件桥接
各个执行器（RAG、Agent、澄清等）在执行过程中需要向前端发送"思考中"或"状态变更"事件。ExecutorEventSupport 提供了统一的桥接方法，把"格式化 + 推送"封装成一步调用：
 */
// ExecutorEventSupport.java —— 执行器事件桥接
public final class ExecutorEventSupport {

    private ExecutorEventSupport() {
    }


    /*
    这个方法做了两件事：
1.把 thinking 文案存到 taskInfo.thinkingSteps() 列表里，方便后续调试和会话记录复用
2.通过 SinkEmitHelper.emitNext 把序列化后的事件写入 SSE sink，前端就能实时收到
     */
    // 发布思考步骤事件
    public static void publishThinking(TaskInfo taskInfo, StreamEventWriter writer, String content) {
        // 任意关键对象为空，或提示内容为空时，不发送事件，避免空指针和无意义前端消息。
        if (taskInfo == null || writer == null || StrUtil.isBlank(content)) {
            return;
        }
        // 先记录到 thinkingSteps 集合，收尾时会一起落库  (// 把思考步骤记录到 taskInfo 的列表中，方便后续回溯)    // 先把 thinking 文案保存到任务上下文，最终调试信息和会话记录可以复用这份列表。
        taskInfo.thinkingSteps().add(content);
        // 再通过 SinkEmitHelper 推送给前端   (// 通过 SinkEmitHelper 往响应式流里发送一条 thinking 类型的 SSE 事件)    再把 thinking 事件写入当前请求的 SSE sink，让前端实时看到执行器状态。
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