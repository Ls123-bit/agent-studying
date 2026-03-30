package org.javaup.enums;

/**
 * 会话主状态枚举。
 *
 * <p>这里描述的是“整个会话当前是否正在执行”，
 * 和 BaseTableData.status 那种 1 正常 / 0 删除的通用数据状态不是一回事。</p>
 */
public enum ChatSessionStatus {

    /**
     * 会话空闲中，可以继续发起下一轮。
     */
    IDLE(1, "空闲"),

    /**
     * 当前会话有一条流式链路正在执行。
     */
    RUNNING(2, "进行中");

    private final int code;
    private final String desc;

    ChatSessionStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static ChatSessionStatus fromCode(Integer code) {
        if (code == null) {
            return IDLE;
        }
        for (ChatSessionStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的会话状态 code: " + code);
    }

    public static boolean isRunning(Integer code) {
        return RUNNING.code == (code == null ? IDLE.code : code);
    }
}
