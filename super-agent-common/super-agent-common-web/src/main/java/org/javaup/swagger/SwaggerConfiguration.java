package org.javaup.swagger;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 配置类
 * @author: 阿星不是程序员
 **/
/**
 * OpenAPI / Swagger 基础配置。
 *
 * <p>主要用于生成接口文档页面的基础信息，
 * 比如标题、版本、描述和联系人。</p>
 */
@Configuration
public class SwaggerConfiguration {

    /**
     * 注册项目的 OpenAPI 元信息。
     */
    @Bean
    public OpenAPI customOpenApi() {

        return new OpenAPI()
                .info(new Info()
                        .title("前端使用")
                        .version("1.0")
                        .description("项目学习")
                        .contact(new Contact()
                                .name("阿星不是程序员")
                        ));
    }
}
