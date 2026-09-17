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


/*
buildLaunchPlan() 的产物，把请求参数标准化后的不可变对象。
对应的数据结构很简单，都是后面会反复用到的字段

 */


@Data
@AllArgsConstructor
public class StreamLaunchPlan {

    private final String question;//标准化后的问题

    private final String conversationId;//会话ID（新建或复用）

    private final ChatQueryMode chatMode;//聊天模式

    private final Long selectedDocumentId;//选中文档的ID

    private final String selectedDocumentName;//选中文档的名称

    private final Long selectedTaskId;//选中的任务ID

    private final String leaseKey;//租约 key（chat:running:{conversationId}）  按 conversationId 生成的 Redis 租约 key，用来做会话级互斥。

    private final String leaseOwnerToken;//租约 owner（UUID） 本次请求自己的持有者令牌，后面续租和释放时要靠它确认“这把锁是不是我拿的”。

    private final LocalDate currentDate;//当前日期

    private final String currentDateText;//格式化日期
}
