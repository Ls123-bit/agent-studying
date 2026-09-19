package org.javaup.ai.manage.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;



/*
Kafka消息投递细节
消息体很简单 就三个id
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentIndexBuildMessage {

    private Long documentId;

    private Long taskId;

    private Long planId;
}
