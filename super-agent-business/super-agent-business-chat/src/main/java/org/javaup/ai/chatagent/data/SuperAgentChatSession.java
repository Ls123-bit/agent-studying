package org.javaup.ai.chatagent.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;

/**
 * 对话会话主表实体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_chat_session")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentChatSession extends BaseTableData {

    /**
     * 主键 id。
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 会话唯一业务编号。
     */
    private String conversationId;

    /**
     * 会话业务状态。
     */
    private Integer sessionStatus;
}
