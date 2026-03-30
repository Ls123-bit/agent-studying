package org.javaup.ai.manage.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.javaup.ai.manage.config.DocumentManageProperties.Kafka;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 文档管理模块 Kafka 配置。
 */
@EnableKafka
@Configuration
@EnableConfigurationProperties(DocumentManageProperties.class)
public class DocumentManageKafkaConfiguration {

    /**
     * 解析与策略推荐主题。
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.manage.kafka", name = "auto-create-topics", havingValue = "true", matchIfMissing = true)
    public NewTopic documentParseTopic(DocumentManageProperties properties) {
        Kafka kafka = properties.getKafka();
        return TopicBuilder.name(kafka.getParseTopic()).partitions(1).replicas(1).build();
    }

    /**
     * 构建索引主题。
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.manage.kafka", name = "auto-create-topics", havingValue = "true", matchIfMissing = true)
    public NewTopic documentIndexTopic(DocumentManageProperties properties) {
        Kafka kafka = properties.getKafka();
        return TopicBuilder.name(kafka.getIndexTopic()).partitions(1).replicas(1).build();
    }
}
