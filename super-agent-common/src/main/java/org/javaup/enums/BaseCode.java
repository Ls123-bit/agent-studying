package org.javaup.enums;

/**
 * 通用接口返回码枚举。
 *
 * <p>这里定义的是跨业务共享的一小组基础状态码，
 * 适合放在 common 层供所有模块复用。</p>
 */
public enum BaseCode {
    /**
     * 请求成功。
     */
    SUCCESS(0, "OK"),

    /**
     * 系统异常。
     */
    SYSTEM_ERROR(-1,"系统异常，请稍后重试"),

    /**
     * 参数校验失败。
     */
    PARAMETER_ERROR(10054,"参数验证异常"),
    ;
    
    private final Integer code;
    
    private String msg = "";
    
    BaseCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
    
    public Integer getCode() {
        return this.code;
    }
    
    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }

    /**
     * 根据 code 反查文案。
     */
    public static String getMsg(Integer code) {
        for (BaseCode re : BaseCode.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }

    /**
     * 根据 code 反查枚举值。
     */
    public static BaseCode getRc(Integer code) {
        for (BaseCode re : BaseCode.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
