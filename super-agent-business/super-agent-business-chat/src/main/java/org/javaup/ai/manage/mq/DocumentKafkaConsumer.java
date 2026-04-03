package org.javaup.ai.manage.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.mq.message.DocumentIndexBuildMessage;
import org.javaup.ai.manage.mq.message.DocumentParseRouteMessage;
import org.javaup.ai.manage.service.DocumentAsyncProcessService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static org.javaup.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * 文档管理模块 Kafka 消费者。
 *
 * <p>这个类本身不承载复杂业务，它的职责是把 Kafka 消息转交给
 * {@link org.javaup.ai.manage.service.DocumentAsyncProcessService}。</p>
 *
 * <p>也就是说，同步接口只需要发消息，这里负责把消息“接住并分发到正确的异步流水线”。</p>
 */
@Slf4j
@Component
public class DocumentKafkaConsumer {

    private final DocumentAsyncProcessService asyncProcessService;

    private final ObjectMapper objectMapper;

    public DocumentKafkaConsumer(DocumentAsyncProcessService asyncProcessService,
                                 ObjectMapper objectMapper) {
        this.asyncProcessService = asyncProcessService;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理“解析 + 策略推荐”消息。
     */
    @KafkaListener(topics = SPRING_INJECT_PREFIX_DISTINCTION_NAME+"-"+"${app.manage.kafka.parse-topic}", groupId = "${app.manage.kafka.group-id}-parse")
    public void consumeParseRoute(String payload) {
        try {
            // 先把 JSON 消息反序列化成标准消息体，再交给异步处理服务。
            DocumentParseRouteMessage message = objectMapper.readValue(payload, DocumentParseRouteMessage.class);
            /*
             * 消费者本身故意保持很薄，只负责把消息路由到正确的应用服务入口。
             * 真正的状态推进、落库和日志记录都集中在 DocumentAsyncProcessService 里统一维护。
             */
            asyncProcessService.handleParseRoute(message.getDocumentId(), message.getTaskId());
        }
        catch (Exception exception) {
            // 这里先记录完整 payload，方便后续排查是消息体脏数据还是异步处理失败。
            log.error("消费解析路由消息失败，payload={}", payload, exception);
        }
    }

    /**
     * 处理“构建索引”消息。
     */
    @KafkaListener(topics = SPRING_INJECT_PREFIX_DISTINCTION_NAME+"-"+"${app.manage.kafka.index-topic}", groupId = "${app.manage.kafka.group-id}-index")
    public void consumeIndexBuild(String payload) {
        try {
            // 索引构建消息除了 documentId、taskId，还必须带上被执行的 planId。
            DocumentIndexBuildMessage message = objectMapper.readValue(payload, DocumentIndexBuildMessage.class);
            /*
             * 解析链和索引链共用同一个消费者类，但会分发到不同的方法。
             * 这样“消息入口”只有一个地方，而“真正业务执行”仍然按链路拆清楚。
             */
            asyncProcessService.handleIndexBuild(message.getDocumentId(), message.getTaskId(), message.getPlanId());
        }
        catch (Exception exception) {
            log.error("消费索引构建消息失败，payload={}", payload, exception);
        }
    }
}
