package org.javaup.ai.manage.config;

import cn.hutool.core.util.StrUtil;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 配置类
 * @author: 阿星不是程序员
 **/
/**
 * 文档管理模块 PGVector 配置。
 *
 * <p>当前业务主库仍然使用 MySQL + MyBatis Plus，
 * 这里额外为向量写入单独创建一个 PostgreSQL 连接支持对象，
 * 避免直接声明第二个 DataSource Bean 之后，导致 Spring Boot 放弃自动装配主库 MySQL。</p>
 */
@Configuration
@EnableConfigurationProperties(DocumentManageProperties.class)
@ConditionalOnProperty(prefix = "app.manage.pgvector", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DocumentManagePgVectorConfiguration {

    /**
     * 创建 PGVector 专用连接支持对象。
     *
     * <p>这里故意不直接注册 DataSource 类型的 Bean。
     * 因为一旦容器里提前有了自定义 DataSource，Spring Boot 就不会再根据 spring.datasource
     * 自动装配主库 MySQL，进而导致数据库初始化脚本跑到 PostgreSQL 上。</p>
     */
    @Bean(name = "documentManagePgVectorJdbcSupport")
    public DocumentManagePgVectorJdbcSupport documentManagePgVectorJdbcSupport(DocumentManageProperties properties) {
        DocumentManageProperties.PgVector pg = properties.getPgVector();
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setJdbcUrl(buildJdbcUrl(pg));
        dataSource.setUsername(pg.getUsername());
        dataSource.setPassword(pg.getPassword());
        dataSource.setPoolName(pg.getPoolName());
        dataSource.setMaximumPoolSize(pg.getMaximumPoolSize());
        dataSource.setMinimumIdle(pg.getMinimumIdle());
        return new DocumentManagePgVectorJdbcSupport(dataSource);
    }

    /**
     * 创建 PGVector 专用 JdbcTemplate。
     */
    @Bean(name = "documentManagePgVectorJdbcTemplate")
    public JdbcTemplate documentManagePgVectorJdbcTemplate(
        @Qualifier("documentManagePgVectorJdbcSupport") DocumentManagePgVectorJdbcSupport jdbcSupport) {
        return jdbcSupport.getJdbcTemplate();
    }

    /**
     * 组装 PostgreSQL JDBC 连接串。
     */
    private String buildJdbcUrl(DocumentManageProperties.PgVector pg) {
        StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://")
            .append(pg.getHost())
            .append(":")
            .append(pg.getPort())
            .append("/")
            .append(pg.getDatabase())
            .append("?stringtype=unspecified");
        if (StrUtil.isNotBlank(pg.getSchema())) {
            jdbcUrl.append("&currentSchema=").append(pg.getSchema());
        }
        return jdbcUrl.toString();
    }

    /**
     * PGVector JDBC 支持对象。
     *
     * <p>内部持有一个独立的 HikariDataSource 和对应的 JdbcTemplate，
     * 并在 Spring 容器关闭时主动释放连接池资源。</p>
     */
    public static class DocumentManagePgVectorJdbcSupport implements DisposableBean {

        private final HikariDataSource dataSource;

        private final JdbcTemplate jdbcTemplate;

        public DocumentManagePgVectorJdbcSupport(HikariDataSource dataSource) {
            this.dataSource = dataSource;
            this.jdbcTemplate = new JdbcTemplate(dataSource);
        }

        public JdbcTemplate getJdbcTemplate() {
            return jdbcTemplate;
        }

        @Override
        public void destroy() {
            dataSource.close();
        }
    }
}
