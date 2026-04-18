package org.javaup.ai.chatagent.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 视图对象
 * @author: 阿星不是程序员
 **/
/**
 * 会话重置结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationResetVo {

    /**
     * 被重置的会话标识。
     */
    private String conversationId;

    /**
     * 重置前是否存在正在运行的任务。
     */
    private boolean stoppedRunningTask;

    /**
     * 物理删除的会话主记录数量。
     */
    private int removedDialogueCount;

    /**
     * 物理删除的轮次明细数量。
     */
    private int removedExchangeCount;

    /**
     * 清理掉的 Graph checkpoint 数量。
     */
    private int removedCheckpointCount;

    /**
     * 操作说明。
     */
    private String message;
}
