package org.javaup.ai.manage.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.mq.message.DocumentIndexBuildMessage;
import org.javaup.ai.manage.mq.message.DocumentParseRouteMessage;
import org.javaup.core.SpringUtil;
import org.javaup.enums.DocumentManageCode;
import org.javaup.exception.SuperAgentFrameException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 消息组件
 * @author: 阿星不是程序员
 **/
/**
 * 文档管理模块 Kafka 生产者。
 *
 * <p>这个类在同步接口与异步执行链路之间起到“切面分割”作用：</p>
 * <p>1. 控制器和应用服务只负责创建任务并投递消息。</p>
 * <p>2. 真正耗时的解析、切块、向量化动作都转交给消费者异步处理。</p>
 *
 * <p>这样前端点击上传或构建索引后，可以很快拿到任务 ID，
 * 后续通过轮询列表和日志接口感知进度。</p>
 */
@AllArgsConstructor
@Component
public class DocumentKafkaProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper;

    private final DocumentManageProperties properties;

    /**
     * 发送解析与策略推荐消息。
     */
    public void sendParseRoute(DocumentParseRouteMessage message) {
        // 这里用 documentId 作为 key，让同一文档相关消息尽量落到同一分区，便于顺序处理。
        send(SpringUtil.getPrefixDistinctionName() + "-" + properties.getKafka().getParseTopic(), String.valueOf(message.getDocumentId()), message);
    }

    /**
     * 发送索引构建消息。
     */
    public void sendIndexBuild(DocumentIndexBuildMessage message) {
        // 索引构建同样按 documentId 作为 key，保持同一文档任务的处理局部有序。
        send(SpringUtil.getPrefixDistinctionName() + "-" + properties.getKafka().getIndexTopic(), String.valueOf(message.getDocumentId()), message);
    }

    /**
     * 统一发送消息，并在发送失败时抛出业务异常。
     */
    private void send(String topic, String key, Object message) {
        try {
            // Kafka 里统一发送 JSON 字符串，便于消费者按消息类型反序列化。
            String payload = objectMapper.writeValueAsString(message);

            /*
             * 这里同步等待 send 结果，而不是 fire-and-forget。
             * 原因是上传和构建索引入口都属于“用户刚点完按钮就期待知道有没有真正入队”的同步接口，
             * 如果消息没发出去却已经向前端返回成功，会让排障非常困难。
             */
            // 这里调用 get() 是为了把“发送失败”同步暴露出来，
            // 避免接口已经返回成功，但消息其实没投递到 Kafka。
            kafkaTemplate.send(topic, key, payload).get();
        } catch (Exception exception) {
            throw new SuperAgentFrameException(DocumentManageCode.KAFKA_SEND_FAILED.getCode(),
                "Kafka 消息发送失败: " + exception.getMessage(), exception);
        }
    }
}
