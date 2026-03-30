package org.javaup.ai.chatagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.javaup.ai.chatagent.data.GraphThread;

/**
 * ReactAgent Graph 线程表 Mapper。
 */
@Mapper
public interface GraphThreadMapper extends BaseMapper<GraphThread> {

    /**
     * 按业务线程名物理删除全部 Graph 线程记录。
     *
     * <p>这里用线程名删，是因为同一个 conversationId 在 release 之后，
     * 框架可能又插入一条新的未释放线程记录。</p>
     */
    @Delete("""
        DELETE FROM GRAPH_THREAD
         WHERE thread_name = #{threadName}
        """)
    int hardDeleteByThreadName(@Param("threadName") String threadName);
}
