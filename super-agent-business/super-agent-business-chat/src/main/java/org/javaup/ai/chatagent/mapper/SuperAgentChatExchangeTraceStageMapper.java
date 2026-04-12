package org.javaup.ai.chatagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.javaup.ai.chatagent.data.SuperAgentChatExchangeTraceStage;

/**
 * 单轮对话执行阶段轨迹 Mapper。
 */
@Mapper
public interface SuperAgentChatExchangeTraceStageMapper extends BaseMapper<SuperAgentChatExchangeTraceStage> {
}
