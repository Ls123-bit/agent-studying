package org.javaup.environment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

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
