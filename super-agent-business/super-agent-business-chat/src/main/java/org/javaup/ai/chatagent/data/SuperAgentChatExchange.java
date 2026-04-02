package org.javaup.ai.chatagent.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;

/**
 * 单轮对话归档明细实体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_chat_exchange")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentChatExchange extends BaseTableData {

    /**
     * 主键 id。
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 所属会话业务编号。
     */
    @TableField("dialogue_code")
    private String conversationId;

    /**
     * 用户问题。
     */
    @TableField("user_prompt")
    private String question;

    /**
     * 助手最终回答。
     */
    @TableField("reply_content")
    private String answer;

    /**
     * thinkingSteps 的 JSON 字符串。
     */
    @TableField("reasoning_note_list")
    private String thinkingSteps;

    /**
     * references 的 JSON 字符串。
     */
    @TableField("source_snapshot_list")
    private String referenceList;

    /**
     * recommendations 的 JSON 字符串。
     */
    @TableField("followup_suggestion_list")
    private String recommendationList;

    /**
     * usedTools 的 JSON 字符串。
     */
    @TableField("tool_trace_list")
    private String usedToolList;

    /**
     * 当前轮业务状态。
     */
    @TableField("exchange_state")
    private Integer turnStatus;

    /**
     * 失败或停止原因。
     */
    @TableField("finish_note")
    private String errorMessage;

    /**
     * 首包耗时。
     */
    @TableField("first_token_latency_ms")
    private Long firstResponseTimeMs;

    /**
     * 总耗时。
     */
    @TableField("total_latency_ms")
    private Long totalResponseTimeMs;
}
