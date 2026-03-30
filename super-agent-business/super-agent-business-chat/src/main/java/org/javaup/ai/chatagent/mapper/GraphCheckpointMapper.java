package org.javaup.ai.chatagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.javaup.ai.chatagent.data.GraphCheckpoint;

/**
 * ReactAgent Graph checkpoint 表 Mapper。
 */
@Mapper
public interface GraphCheckpointMapper extends BaseMapper<GraphCheckpoint> {

    /**
     * 统计某个业务线程名下累计保存了多少条 checkpoint。
     *
     * <p>这里按 thread_name 查，而不是按单个 thread_id 查，
     * 是为了把同一 conversationId 下历史 release 过的线程也一起算进去。</p>
     */
    @Select("""
        SELECT COUNT(c.checkpoint_id)
          FROM GRAPH_THREAD t
          LEFT JOIN GRAPH_CHECKPOINT c ON c.thread_id = t.thread_id
         WHERE t.thread_name = #{threadName}
        """)
    Integer countByThreadName(@Param("threadName") String threadName);

    /**
     * 按业务线程名物理删除对应的全部 checkpoint。
     */
    @Delete("""
        DELETE c
          FROM GRAPH_CHECKPOINT c
          INNER JOIN GRAPH_THREAD t ON c.thread_id = t.thread_id
         WHERE t.thread_name = #{threadName}
        """)
    int hardDeleteByThreadName(@Param("threadName") String threadName);
}
