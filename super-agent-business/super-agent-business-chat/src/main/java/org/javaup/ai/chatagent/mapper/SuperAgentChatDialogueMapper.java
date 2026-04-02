package org.javaup.ai.chatagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.javaup.ai.chatagent.data.SuperAgentChatDialogue;

/**
 * 对话归档主表 Mapper。
 */
@Mapper
public interface SuperAgentChatDialogueMapper extends BaseMapper<SuperAgentChatDialogue> {
}
