package org.javaup.exception;


import jakarta.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.javaup.common.ApiResponse;
import org.javaup.enums.BaseCode;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>统一把控制器层抛出的异常收口成 {@link ApiResponse}，避免各个接口各自 try/catch。</p>
 *
 * <p>当前主要兜底三类异常：</p>
 * <p>1. 业务主动抛出的 {@link SuperAgentFrameException}</p>
 * <p>2. 参数校验失败的 {@link MethodArgumentNotValidException}</p>
 * <p>3. 其他未预期异常</p>
 */
@Slf4j
@RestControllerAdvice
public class DefaultExceptionHandler {

    /**
     * 业务异常。
     *
     * <p>这类异常通常是业务代码明确识别到“当前请求不应该继续”时主动抛出的，
     * 这里保留原始 code 和 message 直接返回给前端。</p>
     */
    @ExceptionHandler(value = SuperAgentFrameException.class)
    public ApiResponse<String> toolkitExceptionHandler(HttpServletRequest request, SuperAgentFrameException superAgentFrameException) {
        log.error("业务异常 错误信息 : {} method : {} url : {} query : {} ", superAgentFrameException.getMessage(), request.getMethod(), getRequestUrl(request), getRequestQuery(request), superAgentFrameException);
        return ApiResponse.error(superAgentFrameException.getCode(), superAgentFrameException.getMessage());
    }

    /**
     * 参数验证异常。
     *
     * <p>把 Spring Validation 产生的字段错误整理成统一结构，
     * 方便前端直接按字段名展示校验失败原因。</p>
     */
    @SneakyThrows
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public ApiResponse<List<ArgumentError>> validExceptionHandler(HttpServletRequest request, MethodArgumentNotValidException ex) {
        log.error("参数验证异常 错误信息 : {} method : {} url : {} query : {} ", ex.getMessage(), request.getMethod(), getRequestUrl(request), getRequestQuery(request), ex);
        BindingResult bindingResult = ex.getBindingResult();
        List<ArgumentError> argumentErrorList = 
                bindingResult.getFieldErrors()
                        .stream()
                        .map(fieldError -> {
                            ArgumentError argumentError = new ArgumentError();
                            argumentError.setArgumentName(fieldError.getField());
                            argumentError.setMessage(fieldError.getDefaultMessage());
                            return argumentError;
                        }).collect(Collectors.toList());
        return ApiResponse.error(BaseCode.PARAMETER_ERROR.getCode(),argumentErrorList);
    }

    /**
     * 兜底处理其他未捕获异常。
     *
     * <p>日志里保留完整异常栈，返回给客户端的则是统一系统错误文案，
     * 避免把内部实现细节直接暴露出去。</p>
     */
    @ExceptionHandler(value = Throwable.class)
    public ApiResponse<String> defaultErrorHandler(HttpServletRequest request, Throwable throwable) {
        log.error("全局异常 错误信息 : {} method : {} url : {} query : {} ", throwable.getMessage(), request.getMethod(), getRequestUrl(request), getRequestQuery(request), throwable);
        return ApiResponse.error();
    }

    /**
     * 提取请求 URL，单独封装只是为了让日志拼装更清晰。
     */
    private String getRequestUrl(HttpServletRequest request) {
        return request.getRequestURL().toString();
    }

    /**
     * 提取请求 query string。
     */
    private String getRequestQuery(HttpServletRequest request){
        return request.getQueryString();
    }
}
