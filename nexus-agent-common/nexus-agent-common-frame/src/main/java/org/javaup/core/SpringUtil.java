package org.javaup.core;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import static org.javaup.constant.Constant.DEFAULT_PREFIX_DISTINCTION_NAME;
import static org.javaup.constant.Constant.PREFIX_DISTINCTION_NAME;


public class SpringUtil implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static ConfigurableApplicationContext configurableApplicationContext;

    public static String getPrefixDistinctionName(){
        return configurableApplicationContext.getEnvironment().getProperty(PREFIX_DISTINCTION_NAME,
                DEFAULT_PREFIX_DISTINCTION_NAME);
    }

    @Override
    public void initialize(final ConfigurableApplicationContext applicationContext) {
        configurableApplicationContext = applicationContext;
    }

    public static <T> T getBean(Class<T> requiredType){
        return configurableApplicationContext.getBean(requiredType);
    }

    public static <T> T getBean(String name, Class<T> requiredType){
        return configurableApplicationContext.getBean(name,requiredType);
    }
}
