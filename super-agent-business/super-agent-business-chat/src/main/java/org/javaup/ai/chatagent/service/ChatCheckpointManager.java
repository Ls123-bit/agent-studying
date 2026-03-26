package org.javaup.ai.chatagent.service;

import java.util.Collection;
import java.util.Optional;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对 Spring AI Alibaba MysqlSaver 的业务侧封装。
 *
 * <p>MysqlSaver 负责 ReactAgent 的 checkpoint 持久化，
 * 这里再包一层的原因是：
 * 1. 业务代码只关心查询 checkpoint、统计 checkpoint、清理指定线程；
 * 2. clearThread 这种“按 thread_name 彻底删掉记录”的动作，框架本身没有现成方法。</p>
 */
@Component
public class ChatCheckpointManager {

    private final MysqlSaver checkpointSaver;
    private final JdbcTemplate jdbcTemplate;

    public ChatCheckpointManager(MysqlSaver checkpointSaver, JdbcTemplate jdbcTemplate) {
        this.checkpointSaver = checkpointSaver;
        this.jdbcTemplate = jdbcTemplate;
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
     * <p>GRAPH_CHECKPOINT 通过外键依赖 GRAPH_THREAD，
     * 因此删掉 thread 记录时，checkpoint 会级联删除。
     * 返回值是删除前的 checkpoint 数量，方便上层接口给用户反馈。</p>
     */
    @Transactional
    public int clearThread(String threadId) {
        /*
         * 先统计数量，再删除线程。
         * 这样接口层既能拿到清理结果反馈，也不会因为先删后查而丢失统计值。
         */
        Integer checkpointCount = jdbcTemplate.queryForObject(
            """
                SELECT COUNT(c.checkpoint_id)
                  FROM GRAPH_THREAD t
                  LEFT JOIN GRAPH_CHECKPOINT c ON c.thread_id = t.thread_id
                 WHERE t.thread_name = ?
                """,
            Integer.class,
            threadId
        );

        /*
         * GRAPH_CHECKPOINT 依赖 GRAPH_THREAD 的外键，
         * 删除线程后，关联的 checkpoint 会自动级联清理。
         */
        jdbcTemplate.update(
            "DELETE FROM GRAPH_THREAD WHERE thread_name = ?",
            threadId
        );
        return checkpointCount != null ? checkpointCount : 0;
    }
}
