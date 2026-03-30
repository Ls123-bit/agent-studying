package org.javaup.ai.chatagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.javaup.ai.chatagent.data.SuperAgentChatTurn;

/**
 * 单轮对话明细 Mapper。
 */
@Mapper
public interface SuperAgentChatTurnMapper extends BaseMapper<SuperAgentChatTurn> {

    /**
     * 物理删除指定会话下的全部轮次。
     */
    @Delete("""
        DELETE FROM super_agent_chat_turn
         WHERE conversation_id = #{conversationId}
        """)
    int hardDeleteByConversationId(@Param("conversationId") String conversationId);
}
