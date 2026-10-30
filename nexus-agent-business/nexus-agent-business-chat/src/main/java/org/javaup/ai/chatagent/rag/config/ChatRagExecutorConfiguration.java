package org.javaup.ai.chatagent.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


@Configuration
public class ChatRagExecutorConfiguration {




    /*
    手写有界的线程池，没有用：Executors.newFixedThreadPool()（其无界队列有 OOM 风险），而是手动 new ThreadPoolExecutor(...)，一次构建了三个按用途隔离的池
     */

    @Bean(name = "chatRagExecutorService", destroyMethod = "shutdown")
    public ExecutorService chatRagExecutorService() {

        return newFixedThreadPool("chat-rag-executor-", 8, 256);//  核心/最大线程=8，队列容量 256 用途：RAG多路检索并行
    }

    @Bean(name = "chatMemorySummaryExecutorService", destroyMethod = "shutdown")
    public ExecutorService chatMemorySummaryExecutorService() {

        return newFixedThreadPool("chat-memory-summary-", 2, 32); //  核心/最大线程=2，队列容量 32 用途：会话摘要异步压缩
    }

    @Bean(name = "chatPostProcessExecutorService", destroyMethod = "shutdown")
    public ExecutorService chatPostProcessExecutorService() {
        return newFixedThreadPool("chat-post-process-", 2, 64);//  核心/最大线程=2，队列容量 64 用途：推荐问题等后置处理
    }



    /*负责生产线程池对象的方法，所以叫做线程池的工厂方法
    这是一个工厂方法，是面向对象里面的一种基本的写法：把创建对象这件事封装在一个方法里面，调用方只说我要一个什么样的东西，不关心是怎么new出来的
    好处：
    1.隐藏构造细节——创建逻辑复杂（参数多、要设置一堆属性）时，调用方不用重复写；
    2.消除重复——多处需要同类对象时，只改一处；
    统一管控——所有产物遵循同一套规则，不会这漏个参数、那配错策略。

    如果没有这个工厂方法，就要写 3 遍完整的 new ThreadPoolExecutor(...)，每遍都要重复配置有界队列、ThreadFactory lambda、拒绝策略——一旦想给所有池加个统一行为（比如线程改成非守护、加 UncaughtExceptionHandler），就得改 3 处。
    有了它之后：输入：线程名前缀、池大小、队列容量 3 个参数
    输出：一个配置完整、带命名线程 + 有界队列 + CallerRunsPolicy 的 ExecutorService
     */
    private ExecutorService newFixedThreadPool(String threadNamePrefix, int poolSize, int queueCapacity) {
        AtomicInteger threadCounter = new AtomicInteger(1);

        return new ThreadPoolExecutor(
            poolSize,//核心=最大线程（固定池，避免线程池的抖动）
            poolSize,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(queueCapacity),//1.有界队列：最多排queneCapacity个任务
            runnable -> {//2.命名ThreadFactory
                Thread thread = new Thread(runnable);
                thread.setName(threadNamePrefix + threadCounter.getAndIncrement());// 如 chat-rag-executor-1，便于 jstack/日志定位
                thread.setDaemon(true);// 守护线程，不阻塞 JVM 退出
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // ③ 拒绝策略：队列满时由调用者线程自己执行
        );
    }
}
