package org.javaup.enums;

/**
 * 对话轮次状态。
 *
 * <p>用于描述一轮聊天在服务端生命周期中的状态变化。</p>
 */
public enum ChatTurnStatus {
    /**
     * 正在执行中。
     */
    RUNNING,
    /**
     * 正常完成。
     */
    COMPLETED,
    /**
     * 执行失败。
     */
    FAILED,
    /**
     * 被主动停止。
     */
    STOPPED
}
