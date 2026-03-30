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
 * 单轮对话明细实体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_chat_turn")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentChatTurn extends BaseTableData {

    /**
     * 主键 id。
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 所属会话业务编号。
     */
    private String conversationId;

    /**
     * 用户问题。
     */
    private String question;

    /**
     * 助手最终回答。
     */
    private String answer;

    /**
     * thinkingSteps 的 JSON 字符串。
     */
    private String thinkingSteps;

    /**
     * references 的 JSON 字符串。
     */
    private String referenceList;

    /**
     * recommendations 的 JSON 字符串。
     */
    private String recommendationList;

    /**
     * usedTools 的 JSON 字符串。
     */
    private String usedToolList;

    /**
     * 当前轮业务状态。
     */
    private Integer turnStatus;

    /**
     * 失败或停止原因。
     */
    private String errorMessage;

    /**
     * 首包耗时。
     */
    private Long firstResponseTimeMs;

    /**
     * 总耗时。
     */
    private Long totalResponseTimeMs;
}
