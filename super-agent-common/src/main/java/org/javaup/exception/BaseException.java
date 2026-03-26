package org.javaup.exception;

/**
 * 业务体系内异常的公共基类。
 *
 * <p>把项目里的自定义异常统一收敛到一个父类，后续如果要在异常体系上追加
 * 统一能力，例如错误码、traceId、国际化消息等，会更容易扩展。</p>
 */
public class BaseException extends RuntimeException{
	
	public BaseException() {
		
	}
	
	public BaseException(String message) {
		super(message);
	}
	
	public BaseException(Throwable cause) {
		super(cause);
	}
	
	public BaseException(String message, Throwable cause) {
		super(message, cause);
	}

	public BaseException(Integer code, String message, Throwable cause) {
		super(message, cause);
	}
}
