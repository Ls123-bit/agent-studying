package org.javaup.ai.chatagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.javaup.ai.chatagent.data.SuperAgentChatMemorySummary;

/**
 * 会话长期记忆摘要快照 Mapper。
 */
@Mapper
public interface SuperAgentChatMemorySummaryMapper extends BaseMapper<SuperAgentChatMemorySummary> {
}
