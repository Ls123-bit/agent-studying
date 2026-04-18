package org.javaup.ai.manage.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 消息组件
 * @author: 阿星不是程序员
 **/
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
