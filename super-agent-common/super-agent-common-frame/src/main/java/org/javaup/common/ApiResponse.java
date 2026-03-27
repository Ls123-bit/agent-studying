package org.javaup.common;

import lombok.Data;
import org.javaup.enums.BaseCode;

import java.io.Serializable;

/**
 * 统一接口返回体。
 *
 * <p>common 模块希望业务接口尽量遵循同一种返回结构：</p>
 * <p>1. {@code code} 表示状态码，0 约定为成功。</p>
 * <p>2. {@code message} 表示给前端或调用方看的提示信息。</p>
 * <p>3. {@code data} 承载真实业务数据。</p>
 *
 * <p>这里把构造函数收窄为 private，统一通过静态工厂方法创建，
 * 是为了让各个业务模块返回结果时风格更一致，也避免手动 new 时漏填字段。</p>
 */
@Data
public class ApiResponse<T> implements Serializable {

    /**
     * 业务状态码。
     */
    private Integer code;

    /**
     * 状态说明。
     */
    private String message;

    /**
     * 真实返回数据。
     */
    private T data;

    private ApiResponse() {}

    /**
     * 自定义错误码 + 文案。
     */
    public static <T> ApiResponse<T> error(Integer code, String message) {
        ApiResponse<T> apiResponse = new ApiResponse<T>();
        apiResponse.code = code;
        apiResponse.message = message;
        return apiResponse;
    }

    /**
     * 使用默认错误码返回错误文案。
     */
    public static <T> ApiResponse<T> error(String message) {
        ApiResponse<T> apiResponse = new ApiResponse<T>();
        apiResponse.code = -100;
        apiResponse.message = message;
        return apiResponse;
    }

    /**
     * 使用默认错误码返回错误数据体。
     */
    public static <T> ApiResponse<T> error(Integer code, T data) {
        ApiResponse<T> apiResponse = new ApiResponse<T>();
        apiResponse.code = -100;
        apiResponse.data = data;
        return apiResponse;
    }

    /**
     * 从枚举错误码构造失败结果。
     */
    public static <T> ApiResponse<T> error(BaseCode baseCode) {
        ApiResponse<T> apiResponse = new ApiResponse<T>();
        apiResponse.code = baseCode.getCode();
        apiResponse.message = baseCode.getMsg();
        return apiResponse;
    }

    /**
     * 从枚举错误码构造失败结果，并携带额外数据。
     */
    public static <T> ApiResponse<T> error(BaseCode baseCode, T data) {
        ApiResponse<T> apiResponse = new ApiResponse<T>();
        apiResponse.code = baseCode.getCode();
        apiResponse.message = baseCode.getMsg();
        apiResponse.data = data;
        return apiResponse;
    }

    /**
     * 默认系统异常返回。
     */
    public static <T> ApiResponse<T> error() {
        ApiResponse<T> apiResponse = new ApiResponse<T>();
        apiResponse.code = -100;
        apiResponse.message = "系统错误，请稍后重试!";
        return apiResponse;
    }

    /**
     * 无数据成功返回。
     */
    public static <T> ApiResponse<T> ok() {
        ApiResponse<T> apiResponse = new ApiResponse<T>();
        apiResponse.code = 0;
        return apiResponse;
    }

    /**
     * 携带数据的成功返回。
     */
    public static <T> ApiResponse<T> ok(T t) {
        ApiResponse<T> apiResponse = new ApiResponse<T>();
        apiResponse.code = 0;
        apiResponse.setData(t);
        return apiResponse;
    }
}
