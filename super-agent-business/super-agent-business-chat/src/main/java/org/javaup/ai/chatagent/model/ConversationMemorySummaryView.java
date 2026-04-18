package org.javaup.ai.chatagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.chatagent.model.memory.ConversationSummaryPayload;

import java.time.Instant;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 视图对象
 * @author: 阿星不是程序员
 **/
/**
 * 会话长期摘要快照视图。
 *
 * <p>这不是给模型直接使用的内部状态对象，
 * 而是给接口层、后台观测页和教学演示使用的只读视图。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMemorySummaryView {

    /**
     * 所属会话编号。
     */
    private String conversationId;

    /**
     * 当前是否已经形成可用的长期摘要。
     */
    private boolean compressionApplied;

    /**
     * 长期摘要已覆盖到的最后一条 exchangeId。
     */
    private long coveredExchangeId;

    /**
     * 长期摘要已覆盖的轮次数。
     */
    private int coveredExchangeCount;

    /**
     * 累计压缩次数。
     */
    private int compressionCount;

    /**
     * 摘要版本号。
     */
    private int summaryVersion;

    /**
     * 当前长期摘要文本。
     */
    private String summaryText;

    /**
     * 结构化长期摘要快照。
     */
    private ConversationSummaryPayload summaryPayload;

    /**
     * 长期摘要覆盖源轮次的最后更新时间。
     */
    private Instant lastSourceEditTime;

    /**
     * 摘要快照自身最近更新时间。
     */
    private Instant updatedAt;
}
