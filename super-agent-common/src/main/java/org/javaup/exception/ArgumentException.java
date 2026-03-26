/**
 * @(#)ParameterException.java 2011-12-20 Copyright 2011 it.kedacom.com, Inc.
 *                             All rights reserved.
 */

package org.javaup.exception;

import lombok.Data;

import java.util.List;

/**
 * 参数异常。
 *
 * <p>当业务层希望以异常形式抛出“参数不合法”时，可以直接使用这个类型，
 * 并额外携带字段级错误明细。</p>
 */

@Data
public class ArgumentException extends BaseException {

    /**
     * 参数异常错误码。
     */
	private Integer code;

    /**
     * 字段级错误详情。
     */
	private List<ArgumentError> argumentErrorList;

    /**
     * 构造带字段错误明细的参数异常。
     */
	public ArgumentException(Integer code, List<ArgumentError> argumentErrorList) {
		this.code = code;
		this.argumentErrorList = argumentErrorList;
	}

	public ArgumentException(String message) {
		super(message);
	}

	public ArgumentException(Integer code, String message) {
		super(message);
		this.code = code;
	}

	public ArgumentException(Throwable cause) {
		super(cause);
	}

	public ArgumentException(String message, Throwable cause) {
		super(message, cause);
	}

	public ArgumentException(Integer code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}
}
