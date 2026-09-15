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
数据类型
记忆加载的最终产物，包含了执行阶段需要的所有历史信息。
主要字段：
长期摘要文本
最近对话原文
回答阶段历史原文
结构化摘要载荷（ConversationSummaryPayload）
 */
public class ConversationMemoryContext {

    private String assembledHistory;

    private String longTermSummary;

    private String recentTranscript;

    private String answerRecentTranscript;

    private ConversationSummaryPayload summaryPayload;

    private Long coveredExchangeId;

    private Integer coveredExchangeCount;

    private Integer compressionCount;

    private boolean compressionApplied;
}
