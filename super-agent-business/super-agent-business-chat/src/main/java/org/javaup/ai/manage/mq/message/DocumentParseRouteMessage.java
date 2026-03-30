package org.javaup.ai.manage.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档解析与策略推荐消息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentParseRouteMessage {

    /**
     * 文档 id。
     */
    private Long documentId;

    /**
     * 任务 id。
     */
    private Long taskId;
}
