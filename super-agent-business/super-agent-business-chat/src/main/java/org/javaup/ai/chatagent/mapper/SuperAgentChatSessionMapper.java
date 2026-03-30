package org.javaup.ai.chatagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.javaup.ai.chatagent.data.SuperAgentChatSession;

/**
 * 会话主表 Mapper。
 */
@Mapper
public interface SuperAgentChatSessionMapper extends BaseMapper<SuperAgentChatSession> {

    /**
     * 物理删除会话主记录。
     *
     * <p>重置会话时我们希望把整条会话彻底清干净，方便同一个 conversationId 重新使用，
     * 因此这里显式提供一个物理删除入口。</p>
     */
    @Delete("""
        DELETE FROM super_agent_chat_session
         WHERE conversation_id = #{conversationId}
        """)
    int hardDeleteByConversationId(@Param("conversationId") String conversationId);
}
