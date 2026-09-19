package org.javaup.ai.chatagent.service;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.javaup.ai.chatagent.data.GraphCheckpoint;
import org.javaup.ai.chatagent.data.GraphThread;
import org.javaup.ai.chatagent.mapper.GraphCheckpointMapper;
import org.javaup.ai.chatagent.mapper.GraphThreadMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/*
Agent检查点管理
ReAct Agent 的执行可能涉及多步推理，检查点机制可以持久化 Agent 的中间状态，支持断点续跑。
存储方式：基于 MySQL 的 MysqlSaver，通过 GraphCheckpointMapper 和 GraphThreadMapper 操作。
检查点的作用：
如果 Agent 执行到一半被中断（比如用户停止、超时），下次可以从检查点恢复继续执行，而不是从头开始。这对于多步推理的场景很有用。
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


    //获取最新检查点
    public Optional<Checkpoint> get(RunnableConfig runnableConfig) {

        return checkpointSaver.get(runnableConfig);
    }
//列出所有检查点
    public Collection<Checkpoint> list(RunnableConfig runnableConfig) {

        return checkpointSaver.list(runnableConfig);
    }
//清除线程的所有检查点，返回删除数量
    @Transactional
    public int clearThread(String threadId) {
        List<GraphThread> threads = graphThreadMapper.selectList(
            new LambdaQueryWrapper<GraphThread>()
                .eq(GraphThread::getThreadName, threadId)
        );
        if (threads == null || threads.isEmpty()) {
            return 0;
        }

        List<String> graphThreadIds = threads.stream()
            .map(GraphThread::getThreadId)
            .toList();

        int checkpointCount = toInt(graphCheckpointMapper.selectCount(
            new LambdaQueryWrapper<GraphCheckpoint>()
                .in(GraphCheckpoint::getThreadId, graphThreadIds)
        ));

        if (checkpointCount > 0) {

            graphCheckpointMapper.delete(
                new LambdaQueryWrapper<GraphCheckpoint>()
                    .in(GraphCheckpoint::getThreadId, graphThreadIds)
            );
        }
        graphThreadMapper.delete(
            new LambdaQueryWrapper<GraphThread>()
                .eq(GraphThread::getThreadName, threadId)
        );
        return checkpointCount;
    }

    private int toInt(Long count) {

        return count == null ? 0 : count.intValue();
    }
}
