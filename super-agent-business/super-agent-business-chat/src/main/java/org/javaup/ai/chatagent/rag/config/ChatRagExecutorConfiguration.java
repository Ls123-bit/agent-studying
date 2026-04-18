package org.javaup.ai.chatagent.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 配置类
 * @author: 阿星不是程序员
 **/
/**
 * 聊天侧 RAG 使用的轻量线程池配置。
 *
 * <p>这里没有引入复杂的线程池配置中心，
 * 只提供一个规模固定、职责明确的执行器，
 * 专门给“子问题并行检索”和“多通道并行检索”使用。</p>
 */
@Configuration
public class ChatRagExecutorConfiguration {

    /**
     * RAG 检索并行执行器。
     */
    @Bean(name = "chatRagExecutorService", destroyMethod = "shutdown")
    public ExecutorService chatRagExecutorService() {
        /*
         * 检索线程池虽然是“固定并发”的语义，
         * 但不再直接用 Executors.newFixedThreadPool(...)：
         * 那种写法会隐藏无界队列，长时间高负载时不利于控制堆积风险。
         */
        return newFixedThreadPool("chat-rag-executor-", 8, 256);
    }

    /**
     * 会话长期摘要异步预热执行器。
     */
    @Bean(name = "chatMemorySummaryExecutorService", destroyMethod = "shutdown")
    public ExecutorService chatMemorySummaryExecutorService() {
        /*
         * 长期摘要预热不需要很高并发，
         * 这里给一个更小的线程池和更短的队列即可。
         */
        return newFixedThreadPool("chat-memory-summary-", 2, 32);
    }

    /**
     * 回答结束后的轻量后处理执行器。
     */
    @Bean(name = "chatPostProcessExecutorService", destroyMethod = "shutdown")
    public ExecutorService chatPostProcessExecutorService() {
        return newFixedThreadPool("chat-post-process-", 2, 64);
    }

    private ExecutorService newFixedThreadPool(String threadNamePrefix, int poolSize, int queueCapacity) {
        AtomicInteger threadCounter = new AtomicInteger(1);
        /*
         * 1. core=max，保持固定并发语义；
         * 2. LinkedBlockingQueue 有界，避免任务无限堆积；
         * 3. CallerRunsPolicy 在极端高峰下会反压提交方，而不是静默丢任务。
         */
        return new ThreadPoolExecutor(
            poolSize,
            poolSize,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(queueCapacity),
            runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName(threadNamePrefix + threadCounter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
