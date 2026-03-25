package org.javaup.ai.chatagent.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.javaup.ai.chatagent.model.SearchReference;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;
import org.springframework.stereotype.Component;

@Component
public class ChatTaskManager {

    private final ConcurrentMap<String, TaskInfo> taskMap = new ConcurrentHashMap<>();

    /**
     * 注册当前正在运行的流式任务。
     *
     * <p>key 使用 conversationId，因此同一个会话在同一时刻只允许有一个活跃任务。</p>
     */
    public boolean register(TaskInfo taskInfo) {
        return taskMap.putIfAbsent(taskInfo.conversationId(), taskInfo) == null;
    }

    public Optional<TaskInfo> get(String conversationId) {
        return Optional.ofNullable(taskMap.get(conversationId));
    }

    public boolean hasRunningTask(String conversationId) {
        return taskMap.containsKey(conversationId);
    }

    public void attachDisposable(String conversationId, Disposable disposable) {
        TaskInfo taskInfo = taskMap.get(conversationId);
        if (taskInfo != null) {
            taskInfo.setDisposable(disposable);
        }
    }

    public void remove(String conversationId) {
        taskMap.remove(conversationId);
    }

    /**
     * 单个流式会话运行时的内存态上下文。
     *
     * <p>这些状态只在当前 JVM 的一次执行过程中使用：
     * answerBuffer 用来拼接正文，
     * thinking/references/usedTools 用来积累过程数据，
     * finalized 用来避免重复收尾。</p>
     */
    public static final class TaskInfo {
        private final String conversationId;
        private final long turnId;
        private final String question;
        private final RunnableConfig runnableConfig;
        private final Sinks.Many<String> sink;
        private final StringBuilder answerBuffer = new StringBuilder();
        private final List<String> thinkingSteps;
        private final List<SearchReference> references;
        private final Set<String> usedTools;
        private final long startTime;
        private final AtomicLong firstResponseTimeMs = new AtomicLong(0L);
        private final AtomicBoolean finalized = new AtomicBoolean(false);
        private volatile Disposable disposable;

        public TaskInfo(String conversationId,
                        long turnId,
                        String question,
                        RunnableConfig runnableConfig,
                        Sinks.Many<String> sink,
                        List<String> thinkingSteps,
                        List<SearchReference> references,
                        Set<String> usedTools,
                        long startTime) {
            this.conversationId = conversationId;
            this.turnId = turnId;
            this.question = question;
            this.runnableConfig = runnableConfig;
            this.sink = sink;
            this.thinkingSteps = thinkingSteps;
            this.references = references;
            this.usedTools = usedTools;
            this.startTime = startTime;
        }

        public String conversationId() {
            return conversationId;
        }

        public long turnId() {
            return turnId;
        }

        public String question() {
            return question;
        }

        public RunnableConfig runnableConfig() {
            return runnableConfig;
        }

        public Sinks.Many<String> sink() {
            return sink;
        }

        public StringBuilder answerBuffer() {
            return answerBuffer;
        }

        public List<String> thinkingSteps() {
            return thinkingSteps;
        }

        public List<SearchReference> references() {
            return references;
        }

        public Set<String> usedTools() {
            return usedTools;
        }

        public long startTime() {
            return startTime;
        }

        public AtomicLong firstResponseTimeMs() {
            return firstResponseTimeMs;
        }

        public AtomicBoolean finalized() {
            return finalized;
        }

        public Disposable disposable() {
            return disposable;
        }

        public void setDisposable(Disposable disposable) {
            this.disposable = disposable;
        }
    }
}
