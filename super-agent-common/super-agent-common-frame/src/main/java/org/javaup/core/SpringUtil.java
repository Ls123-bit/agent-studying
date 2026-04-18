package org.javaup.core;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import static org.javaup.constant.Constant.DEFAULT_PREFIX_DISTINCTION_NAME;
import static org.javaup.constant.Constant.PREFIX_DISTINCTION_NAME;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 工具类
 * @author: 阿星不是程序员
 **/
/**
 * Spring 容器访问工具。
 *
 * <p>在应用启动时把 {@link ConfigurableApplicationContext} 缓存起来，
 * 以便在非 Spring 管理对象或静态上下文中按需取 Bean。</p>
 *
 * <p>这类工具虽然方便，但也意味着调用方对 Spring 容器产生了静态依赖，
 * 因此更适合作为兼容旧代码的桥接方案，而不是优先推荐的新写法。</p>
 */
public class SpringUtil implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static ConfigurableApplicationContext configurableApplicationContext;

    /**
     * 获取业务前缀区分名。
     */
    public static String getPrefixDistinctionName(){
        return configurableApplicationContext.getEnvironment().getProperty(PREFIX_DISTINCTION_NAME,
                DEFAULT_PREFIX_DISTINCTION_NAME);
    }

    @Override
    public void initialize(final ConfigurableApplicationContext applicationContext) {
        configurableApplicationContext = applicationContext;
    }

    /**
     * 按类型获取 Bean。
     */
    public static <T> T getBean(Class<T> requiredType){
        return configurableApplicationContext.getBean(requiredType);
    }

    /**
     * 按名称 + 类型获取 Bean。
     */
    public static <T> T getBean(String name, Class<T> requiredType){
        return configurableApplicationContext.getBean(name,requiredType);
    }
}
