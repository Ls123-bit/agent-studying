package org.javaup.ai.chatagent.service;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ChatRuntimeRegistry {

    /**
     * 运行中的流式任务只保存在当前 JVM 内存里。
     *
     * <p>当前注册表保存“本机执行现场”，让 stopConversation(...)、
     * cleanup(...) 和流式回调能够围绕同一份运行态对象协作。</p>
     */
    private final ConcurrentMap<String, TaskInfo> taskMap = new ConcurrentHashMap<>();

    /**
     * 注册当前 JVM 内的一条运行中任务。
     *
     * <p>key 使用 conversationId，putIfAbsent 用来保证同一个 JVM 内
     * 只登记一份对应会话的运行态对象。</p>
     */
    public boolean register(TaskInfo taskInfo) {
        return taskMap.putIfAbsent(taskInfo.conversationId(), taskInfo) == null;
    }

    public Optional<TaskInfo> get(String conversationId) {
        return Optional.ofNullable(taskMap.get(conversationId));
    }

    public void remove(String conversationId) {
        /*
         * 会话完成、失败或停止后都必须及时移除本机运行态，
         * 否则后续 stopConversation(...) 或同会话新请求可能拿到陈旧上下文。
         */
        taskMap.remove(conversationId);
    }
}
