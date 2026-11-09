package org.javaup.ai.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 后台管理登录配置。
 */
@Data
@ConfigurationProperties(prefix = "app.admin-auth")
public class AdminAuthProperties {

    /**
     * 后台登录用户名。
     */
    private String username = "admin";

    /**
     * 后台登录密码。必须通过环境变量 NEXUS_AGENT_ADMIN_PASSWORD 注入，无默认值。
     */
    private String password = "";

    /**
     * JWT 签名密钥。必须通过环境变量 NEXUS_AGENT_ADMIN_TOKEN_SECRET 注入，无默认值。
     */
    private String tokenSecret = "";

    /**
     * token 有效期，单位分钟。
     */
    private Long tokenExpireMinutes = 720L;
}
