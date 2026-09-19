package org.javaup.ai.manage.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/*
就两个字段
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentParseRouteMessage {

    private Long documentId;

    private Long taskId;
}
