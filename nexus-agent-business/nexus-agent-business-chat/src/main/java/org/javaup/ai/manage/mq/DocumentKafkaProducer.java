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


@AllArgsConstructor
@Component
/*
Kafka消息驱动
 */
public class DocumentKafkaProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper;

    private final DocumentManageProperties properties;




    /**
     * 发送"解析路由"消息。
     * <p>
     * 这条消息由文档上传链路触发，代表"原始文件已经上传并入库，可以进入异步解析与策略推荐阶段"。
     * </p>
     */
    public void sendParseRoute(DocumentParseRouteMessage message) {//发送解析任务消息
        //topic 名称是通过 SpringUtil.getPrefixDistinctionName() 加上配置的 topic 名拼接而成的，
        // 这样不同环境（dev、test、prod）的 topic 不会互相干扰。
        // 消息的 key 用的是 documentId，这样同一文档的消息会落到同一个 partition，保证顺序性。
        send(SpringUtil.getPrefixDistinctionName() + "-" + properties.getKafka().getParseTopic(), String.valueOf(message.getDocumentId()), message);
    }







    /*
    kafka的消息投递
    发送方法很简单 序列化成json然后同步发送

     */


    public void sendIndexBuild(DocumentIndexBuildMessage message) {//发送索引构建消息

        send(SpringUtil.getPrefixDistinctionName() + "-" + properties.getKafka().getIndexTopic(), String.valueOf(message.getDocumentId()), message);
    }
    /**
     * 执行一次底层 Kafka 消息发送。
     * <p>
     * 这里统一完成对象序列化和同步发送确认；如果发送失败，则直接抛出业务异常，
     * 让上层知道消息并没有真正进入异步链路。
     * </p>
     */
    private void send(String topic, String key, Object message) {
        try {
            // 先把消息对象序列化为 JSON，消费方再按对应消息类型反序列化。
            String payload = objectMapper.writeValueAsString(message);
            // 这里使用 get() 等待发送结果，确保上传接口返回成功前，消息已经被 broker 接收确认。
            /*
            注意这里用了 .get() 做同步等待——kafkaTemplate.send() 本身是异步的，返回一个 Future，
            调用 .get() 会阻塞当前线程直到 broker 确认收到消息。
            这样做的好处是：如果 Kafka 发送失败，上传接口会直接返回错误，而不是"假装成功"但消息其实丢了。
             */
            /*
            注意这里用了 .get()
            kafkaTemplate.send() 返回的是 Future，调用 .get() 会阻塞等待发送结果。这样做的好处是：如果 Kafka 发送失败，异常会直接抛出来，整个事务会回滚，不会出现"任务创建了但消息没发出去"的不一致状态。
             */
            kafkaTemplate.send(topic, key, payload).get();
        } catch (Exception exception) {
            throw new SuperAgentFrameException(DocumentManageCode.KAFKA_SEND_FAILED.getCode(),
                "Kafka 消息发送失败: " + exception.getMessage(), exception);
        }
    }
}







