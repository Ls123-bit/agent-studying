package org.javaup.ai.chatagent.model.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 编排阶段真正使用的会话记忆上下文
 * @author: 阿星不是程序员
 **/

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor


/*
数据类型    会话记忆上下文对象
记忆加载的最终产物，包含了执行阶段需要的所有历史信息。
主要字段：
长期摘要文本
最近对话原文
回答阶段历史原文
结构化摘要载荷（ConversationSummaryPayload）
 */
public class ConversationMemoryContext {

    private String assembledHistory;//长期摘要+最近窗口    供问题改写和路由使用

    private String longTermSummary;//长期摘要，总结了整个会话的主题和关键信息     单独保留，供需要时使用

    private String recentTranscript;//近期对话窗口，包含最近几轮的完整对话（问题与回答）     供问题改写和路由使用

    private String answerRecentTranscript;//回答窗口，只包含最近几轮的回答部分   供答案生成使用

    private ConversationSummaryPayload summaryPayload;//结构化摘要    供后续规划阶段读取

    private Long coveredExchangeId;//压缩覆盖到的最后一轮 ID      记录压缩进度

    private Integer coveredExchangeCount;//压缩覆盖的轮次数量     记录压缩进度

    private Integer compressionCount;//压缩次数     记录压缩进度

    private boolean compressionApplied;//是否应用了历史压缩   标记是否有长期摘要
}
