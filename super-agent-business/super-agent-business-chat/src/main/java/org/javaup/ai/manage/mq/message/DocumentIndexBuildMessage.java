package org.javaup.ai.manage.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档索引构建消息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentIndexBuildMessage {

    /**
     * 文档 id。
     */
    private Long documentId;

    /**
     * 任务 id。
     */
    private Long taskId;

    /**
     * 方案 id。
     */
    private Long planId;
}
