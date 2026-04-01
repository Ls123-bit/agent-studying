package org.javaup.ai.chatagent.service;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import lombok.Data;
import org.javaup.ai.chatagent.model.SearchReference;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个流式会话运行时的内存态上下文。
 *
 * <p>这些状态只在当前 JVM 的一次执行过程中使用：
 * answerBuffer 用来拼接正文，
 * thinking/references/usedTools 用来积累过程数据，
 * finalized 用来避免重复收尾。</p>
 *
 * <p>其中 leaseKey / leaseOwnerToken 代表这条任务在 Redis 中对应的集群执行资格，
 * disposable / leaseRenewalDisposable 则代表这条任务在当前 JVM 中的两个“活对象”：
 * 一个是真正的 Flux 订阅，一个是续租定时任务。</p>
 */
@Data
public final class TaskInfo {
    private final String conversationId;
    private final long turnId;
    private final String question;
    private final RunnableConfig runnableConfig;
    private final Sinks.Many<String> sink;
    private final String leaseKey;
    private final String leaseOwnerToken;
    private final StringBuilder answerBuffer = new StringBuilder();
    private final List<String> thinkingSteps;
    private final List<SearchReference> references;
    private final Set<String> usedTools;
    private final long startTime;

    /**
     * 首字耗时和 finalized 都是跨线程读写的运行态指标，
     * 因此使用原子类型保证并发下的数据一致性。
     */
    private final AtomicLong firstResponseTimeMs = new AtomicLong(0L);
    private final AtomicBoolean finalized = new AtomicBoolean(false);
    private volatile Disposable disposable;
    private volatile Disposable leaseRenewalDisposable;

    public TaskInfo(String conversationId,
                    long turnId,
                    String question,
                    RunnableConfig runnableConfig,
                    Sinks.Many<String> sink,
                    String leaseKey,
                    String leaseOwnerToken,
                    List<String> thinkingSteps,
                    List<SearchReference> references,
                    Set<String> usedTools,
                    long startTime) {
        this.conversationId = conversationId;
        this.turnId = turnId;
        this.question = question;
        this.runnableConfig = runnableConfig;
        this.sink = sink;
        this.leaseKey = leaseKey;
        this.leaseOwnerToken = leaseOwnerToken;
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

    public String leaseKey() {
        return leaseKey;
    }

    public String leaseOwnerToken() {
        return leaseOwnerToken;
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
    
    public Disposable leaseRenewalDisposable() {
        return leaseRenewalDisposable;
    }
    
}