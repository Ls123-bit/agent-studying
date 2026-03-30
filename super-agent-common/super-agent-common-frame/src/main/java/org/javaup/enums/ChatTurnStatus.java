package org.javaup.enums;

/**
 * 单轮对话业务状态枚举。
 *
 * <p>数据库里存数字 code，接口层仍然继续传递语义化的枚举值，
 * 这样前端和日志阅读起来直观，表结构也满足“状态字段用数字”的要求。</p>
 */
public enum ChatTurnStatus {

    /**
     * 当前轮正在执行中。
     */
    RUNNING(1, "进行中"),

    /**
     * 当前轮正常完成。
     */
    COMPLETED(2, "已完成"),

    /**
     * 当前轮执行失败。
     */
    FAILED(3, "失败"),

    /**
     * 当前轮被主动停止。
     */
    STOPPED(4, "已停止");

    private final int code;
    private final String desc;

    ChatTurnStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static ChatTurnStatus fromCode(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("轮次状态 code 不能为空");
        }
        for (ChatTurnStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的轮次状态 code: " + code);
    }
}
