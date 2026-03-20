package org.javaup;

import org.javaup.ai.config.MilvusDemoProperties;
import org.javaup.hybrid.config.HybridMilvusProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Spring AI + Milvus 向量检索示例启动类。
 */
@SpringBootApplication
@EnableConfigurationProperties({MilvusDemoProperties.class, HybridMilvusProperties.class})
public class ExampleSpringAiRagMilvusApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExampleSpringAiRagMilvusApplication.class, args);
    }

}
