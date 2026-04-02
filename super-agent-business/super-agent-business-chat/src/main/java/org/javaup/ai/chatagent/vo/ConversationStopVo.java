package org.javaup.ai.chatagent.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 停止会话结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationStopVo {

    /**
     * 会话标识。
     */
    private String conversationId;

    /**
     * 是否真正停止了一个运行中的任务。
     */
    private boolean stopped;

    /**
     * 结果说明。
     */
    private String message;
}
