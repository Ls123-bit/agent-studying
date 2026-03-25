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
        return checkpointSaver.get(runnableConfig);
    }

    public Collection<Checkpoint> list(RunnableConfig runnableConfig) {
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
        jdbcTemplate.update(
            "DELETE FROM GRAPH_THREAD WHERE thread_name = ?",
            threadId
        );
        return checkpointCount != null ? checkpointCount : 0;
    }
}
