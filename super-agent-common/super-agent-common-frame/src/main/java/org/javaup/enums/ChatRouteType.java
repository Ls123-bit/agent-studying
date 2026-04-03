package org.javaup.enums;

/**
 * 前置编排阶段的路由判定结果。
 *
 * <p>ExecutionMode 面向“最终执行器选择”，
 * ChatRouteType 面向“为什么走这条路”。</p>
 */
public enum ChatRouteType {
    /**
     * 判断结果
     * */
    KNOWLEDGE(1,""),
    CLARIFY(2,"clarify"),
    OPEN_CHAT(3,"open_chat");
    
    private final int code;
    private final String desc;
    
    ChatRouteType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
    
    public int getCode() {
        return code;
    }
    
    public String getDesc() {
        return desc;
    }
    
    public static ChatRouteType fromCode(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("路由判定结果 code: " + code);
        }
        for (ChatRouteType chatRouteType : values()) {
            if (chatRouteType.code == code) {
                return chatRouteType;
            }
        }
        throw new IllegalArgumentException("路由判定结果 code: " + code);
    }
}
