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

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
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
        List<GraphThread> threads = graphThreadMapper.selectList(
            new LambdaQueryWrapper<GraphThread>()
                .eq(GraphThread::getThreadName, threadId)
        );
        if (threads == null || threads.isEmpty()) {
            return 0;
        }

        /*
         * 先把所有匹配的 graph thread id 摘出来，
         * 后续 checkpoint 删除就不再依赖 join SQL，而是完全走 MyBatis-Plus wrapper。
         *
         * 这样 reset 链路在“操作数据方式”上会更统一，也更符合你希望尽量使用 MP 的要求。
         */
        List<String> graphThreadIds = threads.stream()
            .map(GraphThread::getThreadId)
            .toList();

        int checkpointCount = toInt(graphCheckpointMapper.selectCount(
            new LambdaQueryWrapper<GraphCheckpoint>()
                .in(GraphCheckpoint::getThreadId, graphThreadIds)
        ));

        if (checkpointCount > 0) {
            /*
             * 这里先删 checkpoint，再删 thread，
             * 不是因为数据库外键要求，而是业务上希望任何时刻都不要留下悬挂的 checkpoint 记录。
             */
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
        /*
         * MyBatis-Plus 的 selectCount 返回 Long，
         * 这里统一在边界层转换成 int，避免上层 reset 结果对象到处处理包装类型。
         */
        return count == null ? 0 : count.intValue();
    }
}
