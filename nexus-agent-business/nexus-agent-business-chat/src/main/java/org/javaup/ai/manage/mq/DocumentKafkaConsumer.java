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
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 消息组件
 * @author: 阿星不是程序员
 **/
/*
Kafka消费者：消息入口（Consumer本身不做任何业务逻辑，纯粹是“反序列化+转发”）
消费端的入口在 DocumentKafkaConsumer，它的职责很单一——把 JSON 消息反序列化，然后转交给异步处理服务。

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
     * 消费"解析路由"消息。
     * <p>
     * 这一步是上传完成后的异步链入口：收到消息后，会把 documentId 和 taskId 交给异步处理服务，
     * 继续执行文档下载、正文解析、结构节点生成、策略推荐等步骤。
     * </p>
     */

    //Kafka消费端的代码非常简洁，就是反序列化消息然后调用异步处理服务
    //topic名称是：环境前缀-配置的parseTopic，和上传端发送时用的是同一个topic     groupId带了-parse后缀，和索引构建的消费组区分开
    @KafkaListener(topics = SPRING_INJECT_PREFIX_DISTINCTION_NAME+"-"+"${app.manage.kafka.parse-topic}", groupId = "${app.manage.kafka.group-id}-parse")
    public void consumeParseRoute(String payload) {//消费解析消息，调用 handleParseRoute() 监听topic=parse-topic
        try {//整个方法用 try-catch 包住，消费失败只打日志不抛异常——这是为了防止一条坏消息把整个消费线程搞挂


            // 先把 JSON 还原成强类型消息对象，避免后续处理层直接面对原始字符串。
            DocumentParseRouteMessage message = objectMapper.readValue(payload, DocumentParseRouteMessage.class);
            // 真正的业务推进放到异步处理服务中，这里只承担"消费并转发"的职责。
            asyncProcessService.handleParseRoute(message.getDocumentId(), message.getTaskId());
        }
        catch (Exception exception) {
            // 消费失败只记录日志，不让异常继续向外冒泡破坏监听线程。
            log.error("消费解析路由消息失败，payload={}", payload, exception);
        }
    }

    @KafkaListener(topics = SPRING_INJECT_PREFIX_DISTINCTION_NAME+"-"+"${app.manage.kafka.index-topic}", groupId = "${app.manage.kafka.group-id}-index")
    public void consumeIndexBuild(String payload) {//消费索引消息，调用 handleIndexBuild() 监听topic=index-topic


        try {

            DocumentIndexBuildMessage message = objectMapper.readValue(payload, DocumentIndexBuildMessage.class);

            asyncProcessService.handleIndexBuild(message.getDocumentId(), message.getTaskId(), message.getPlanId());
        }
        catch (Exception exception) {
            log.error("消费索引构建消息失败，payload={}", payload, exception);
        }
    }
}
