package org.javaup.database.mybatisplus;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
/**
 * MyBatis-Plus 自动配置。
 *
 * <p>这里集中注册 common 层约定好的两个基础能力：</p>
 * <p>1. 审计字段自动填充</p>
 * <p>2. MySQL 分页拦截器</p>
 */
public class MybatisPlusAutoConfiguration {

    /**
     * 注册元对象处理器，用于 createTime / editTime 等字段自动填充。
     */
    @Bean
    public MetaObjectHandler metaObjectHandler(){
        return new MybatisPlusMetaObjectHandler();
    }

    /**
     * 注册分页插件。
     *
     * <p>当前按 MySQL 方言处理分页 SQL。</p>
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
