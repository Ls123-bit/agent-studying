package org.javaup.ai.chatagent.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
        AtomicInteger threadCounter = new AtomicInteger(1);
        /*
         * 这里把线程数开到 8，是因为当前检索实现同时存在两级并发：
         * 1. 子问题之间并行。
         * 2. 同一子问题下的向量 / 关键词通道并行。
         *
         * 如果线程数过小，外层任务把线程全部占满后，内层通道任务就可能拿不到执行机会。
         */
        return Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable);
            /*
             * Java 17 还没有 Thread.threadId() 这种便捷 API，
             * 这里用一个简单的自增计数器来保证线程命名稳定、可读。
             */
            thread.setName("chat-rag-executor-" + threadCounter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
    }
}
