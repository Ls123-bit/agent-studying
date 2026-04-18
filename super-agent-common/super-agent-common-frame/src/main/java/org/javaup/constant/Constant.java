package org.javaup.constant;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 常量类
 * @author: 阿星不是程序员
 **/
/**
 * common 模块里的通用常量。
 */
public class Constant {

    /**
     * 前缀区分名的配置项 key。
     */
    public static final String PREFIX_DISTINCTION_NAME = "prefix.distinction.name";

    /**
     * 前缀区分名的默认值。
     */
    public static final String DEFAULT_PREFIX_DISTINCTION_NAME = "super-agent";
    
    public static final String SPRING_INJECT_PREFIX_DISTINCTION_NAME = "${"+PREFIX_DISTINCTION_NAME+":"+DEFAULT_PREFIX_DISTINCTION_NAME+"}";

}
