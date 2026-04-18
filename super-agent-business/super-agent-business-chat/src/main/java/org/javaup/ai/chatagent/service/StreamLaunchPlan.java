package org.javaup.ai.chatagent.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.javaup.enums.ChatQueryMode;

import java.time.LocalDate;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
/**
 * 单次流式会话启动蓝图。
 */
@Data
@AllArgsConstructor
public class StreamLaunchPlan {

    private final String question;

    private final String conversationId;

    /**
     * 当前这一轮请求是“文档问答”还是“开放式提问”。
     *
     * <p>启动蓝图阶段就把模式固定下来，
     * 后面的会话启动、执行计划编排和最终归档都只消费这一份事实来源。</p>
     */
    private final ChatQueryMode chatMode;

    private final Long selectedDocumentId;

    private final String selectedDocumentName;

    /**
     * 文档问答模式下，当前选中文档所对应的可检索索引任务。
     */
    private final Long selectedTaskId;

    private final String leaseKey;

    private final String leaseOwnerToken;

    private final LocalDate currentDate;

    private final String currentDateText;
}
