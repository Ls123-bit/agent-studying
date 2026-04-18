package org.javaup.environment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: Spring 环境后置处理器
 * @author: 阿星不是程序员
 **/
/**
 * Spring 环境后置处理器。
 *
 * <p>这里在应用启动早期打开 {@code allowBeanDefinitionOverriding}，
 * 允许后注册的 Bean 覆盖先注册的 Bean。</p>
 *
 * <p>这个能力通常用于基础模块提供默认实现，而业务模块按需覆盖默认 Bean。</p>
 */
public class SpringEnvironment implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        application.setAllowBeanDefinitionOverriding(true);
    }
}
