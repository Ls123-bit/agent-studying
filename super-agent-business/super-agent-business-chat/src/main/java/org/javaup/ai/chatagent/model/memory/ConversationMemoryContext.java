package org.javaup.ai.chatagent.model.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 编排阶段真正使用的会话记忆上下文。
 *
 * <p>它既保留长期摘要，也保留最近几轮原文窗口，
 * 最终会被 ChatPreparationOrchestrator 组装成历史上下文，
 * 供路由、问题改写和知识域解析复用。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMemoryContext {

    /**
     * 组装后的最终历史上下文文本。
     */
    private String assembledHistory;

    /**
     * 长期摘要文本。
     */
    private String longTermSummary;

    /**
     * 最近几轮原文窗口。
     */
    private String recentTranscript;

    /**
     * 回答阶段可安全复用的最近上下文。
     *
     * <p>这里尽量只保留用户问题和少量稳定承接信息，
     * 避免把上一轮 assistant 输出、失败文案、停止原因直接混进当前回答提示词。</p>
     */
    private String answerRecentTranscript;

    /**
     * 结构化长期摘要。
     */
    private ConversationSummaryPayload summaryPayload;

    /**
     * 长期摘要已覆盖到的最后一条 exchangeId。
     */
    private Long coveredExchangeId;

    /**
     * 长期摘要已覆盖的轮次数。
     */
    private Integer coveredExchangeCount;

    /**
     * 累计发生过多少次压缩。
     */
    private Integer compressionCount;

    /**
     * 当前是否已经启用了长期摘要压缩。
     */
    private boolean compressionApplied;
}
