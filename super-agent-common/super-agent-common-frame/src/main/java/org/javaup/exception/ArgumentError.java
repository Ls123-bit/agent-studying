package org.javaup.exception;

import lombok.Data;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 异常类
 * @author: 阿星不是程序员
 **/
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
