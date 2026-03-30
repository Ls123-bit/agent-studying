package org.javaup.ai.chatagent.service;

import java.util.Collection;
import java.util.Optional;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import org.javaup.ai.chatagent.mapper.GraphCheckpointMapper;
import org.javaup.ai.chatagent.mapper.GraphThreadMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对 Spring AI Alibaba MysqlSaver 的业务侧封装。
 *
 * <p>MysqlSaver 负责 ReactAgent 的 checkpoint 持久化，
 * 这里再包一层的原因是：
 * 1. 业务代码只关心查询 checkpoint、统计 checkpoint、清理指定线程；
 * 2. clearThread 这种“按 thread_name 彻底删掉记录”的动作，框架本身没有现成方法。</p>
 *
 * <p>也就是说：</p>
 * <p>- 正常的 checkpoint 读写，继续交给 Spring AI Alibaba 的 MysqlSaver；</p>
 * <p>- 业务侧的“按会话统计 / 按会话彻底清理”，由当前这个管理器补一层。</p>
 */
@Component
public class ChatCheckpointManager {

    private final MysqlSaver checkpointSaver;
    private final GraphCheckpointMapper graphCheckpointMapper;
    private final GraphThreadMapper graphThreadMapper;

    public ChatCheckpointManager(MysqlSaver checkpointSaver,
                                 GraphCheckpointMapper graphCheckpointMapper,
                                 GraphThreadMapper graphThreadMapper) {
        this.checkpointSaver = checkpointSaver;
        this.graphCheckpointMapper = graphCheckpointMapper;
        this.graphThreadMapper = graphThreadMapper;
    }

    public Optional<Checkpoint> get(RunnableConfig runnableConfig) {
        /*
         * 直接复用 MysqlSaver 的读取能力，让业务层不用感知底层 checkpoint 表结构。
         */
        return checkpointSaver.get(runnableConfig);
    }

    public Collection<Checkpoint> list(RunnableConfig runnableConfig) {
        /*
         * list 常用于会话详情和排查场景，用来观察当前线程累计保存了多少个 checkpoint。
         */
        return checkpointSaver.list(runnableConfig);
    }

    /**
     * 按会话线程清理所有 checkpoint。
     *
     * <p>当前表结构不再依赖数据库外键级联删除，
     * 因此这里显式按“先删 checkpoint，再删 thread”的顺序清理。
     * 返回值是删除前的 checkpoint 数量，方便上层接口给用户反馈。</p>
     */
    @Transactional
    public int clearThread(String threadId) {
        /*
         * 先统计数量，再删除线程。
         * 这样接口层既能拿到清理结果反馈，也不会因为先删后查而丢失统计值。
         *
         * 这里虽然删的是 Spring AI Alibaba 的 GRAPH_THREAD / GRAPH_CHECKPOINT，
         * 但动作并不是框架重复做一遍，而是在补“按业务会话彻底重置”的能力。
         *
         * 框架自己的 MysqlSaver 对外公开的是：
         * - get(...)
         * - list(...)
         * - put(...)
         * - release(...)
         *
         * 其中 release(...) 只是把 GRAPH_THREAD.is_released 标成 true，
         * 并不会物理删除整条线程及其 checkpoint。
         * 而当前业务的 resetConversation(...) 需要的是“彻底清空这条会话的 Agent 运行态”，
         * 所以这里仍然要自己做物理删除，只是实现方式改成了 MyBatis Plus。
         */
        Integer checkpointCount = graphCheckpointMapper.countByThreadName(threadId);

        /*
         * 先删子表 GRAPH_CHECKPOINT，再删主表 GRAPH_THREAD。
         * 这样即使数据库层没有外键约束，也不会留下悬挂的 checkpoint 记录。
         */
        graphCheckpointMapper.hardDeleteByThreadName(threadId);
        graphThreadMapper.hardDeleteByThreadName(threadId);
        return checkpointCount != null ? checkpointCount : 0;
    }
}
