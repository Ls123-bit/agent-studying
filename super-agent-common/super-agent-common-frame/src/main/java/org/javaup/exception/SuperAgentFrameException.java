package org.javaup.exception;

import org.javaup.common.ApiResponse;
import org.javaup.enums.BaseCode;
import lombok.Data;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 异常类
 * @author: 阿星不是程序员
 **/
/**
 * 通用业务异常。
 *
 * <p>这是项目里最常用的业务失败异常，既能携带错误码，也能携带用户可读文案。
 * Controller 层最终会被 {@link DefaultExceptionHandler} 统一转成 {@link ApiResponse}。</p>
 */
@Data
public class SuperAgentFrameException extends BaseException {

    /**
     * 业务错误码。
     */
	private Integer code;

    /**
     * 业务错误信息。
     */
	private String message;

	public SuperAgentFrameException() {
		super();
	}

	public SuperAgentFrameException(String message) {
		super(message);
	}
	
	
	public SuperAgentFrameException(String code, String message) {
		super(message);
		this.code = Integer.parseInt(code);
		this.message = message;
	}
	
	public SuperAgentFrameException(Integer code, String message) {
		super(message);
		this.code = code;
		this.message = message;
	}
	
	public SuperAgentFrameException(BaseCode baseCode) {
		super(baseCode.getMsg());
		this.code = baseCode.getCode();
		this.message = baseCode.getMsg();
	}

    /**
     * 允许把下游返回的统一响应结构再包装成异常。
     */
	public SuperAgentFrameException(ApiResponse apiResponse) {
		super(apiResponse.getMessage());
		this.code = apiResponse.getCode();
		this.message = apiResponse.getMessage();
	}

	public SuperAgentFrameException(Throwable cause) {
		super(cause);
	}

	public SuperAgentFrameException(String message, Throwable cause) {
		super(message, cause);
		this.message = message;
	}

	public SuperAgentFrameException(Integer code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
		this.message = message;
	}
}
