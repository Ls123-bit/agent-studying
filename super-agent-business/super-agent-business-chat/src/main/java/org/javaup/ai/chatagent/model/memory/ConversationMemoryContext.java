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
/**
 * 编排阶段真正使用的会话记忆上下文。
 *
 * <p>它既保留长期摘要，也保留最近几轮原文窗口，
 * 最终会被 ChatPreparationOrchestrator 组装成历史上下文，
 * 供问题改写和文档检索规划复用。</p>
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
     * <p>这里当前只保留最近几轮用户问题，
     * 专门服务“这个问题 / 上面第二点”这类承接式追问的指代理解，
     * 不再把历史助手回答继续当作事实来源带入当前文档问答 Prompt。</p>
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
