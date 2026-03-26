package org.javaup.enums;

/**
 * 通用是/否状态枚举。
 *
 * <p>适合数据库或接口里用 1/0 表示布尔语义的场景。</p>
 */
public enum BusinessStatus {
    /**
     * 是。
     */
    YES(1,"是"),
    /**
     * 否。
     */
    NO(0,"否")
    ;

    private Integer code;

    private String msg;

    BusinessStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public Integer getCode() {
        return code;
    }
    
    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }

    /**
     * 根据 code 找中文描述。
     */
    public static String getMsg(Integer code) {
        for (BusinessStatus re : BusinessStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }

    /**
     * 根据 code 找枚举值本身。
     */
    public static BusinessStatus getRc(Integer code) {
        for (BusinessStatus re : BusinessStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
