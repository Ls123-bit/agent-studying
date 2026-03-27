package org.javaup.exception;

import lombok.Data;

/**
 * 单个字段的参数错误描述。
 *
 * <p>通常和 {@link ArgumentException} 或全局参数校验异常处理器一起使用，
 * 表示“哪个字段错了，以及为什么错”。</p>
 */
@Data
public class ArgumentError {

    /**
     * 出错的字段名。
     */
	private String argumentName;

    /**
     * 对应的校验失败信息。
     */
	private String message;
}
