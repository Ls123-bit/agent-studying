package org.javaup.ai.chatagent.model.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期会话摘要的结构化载体。
 *
 * <p>这里刻意不只保存一段纯文本，
 * 而是把“用户目标、已确认事实、待跟进问题”等长期有效信息拆开保存，
 * 方便后续继续扩展更细的检索路由或元数据过滤能力。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSummaryPayload {

    /**
     * 面向编排器直接可用的精炼摘要正文。
     */
    private String summary;

    /**
     * 当前这条会话长期关注的主目标。
     */
    private String conversationGoal;

    /**
     * 已确认且跨轮仍然稳定有效的事实。
     */
    @Builder.Default
    private List<String> stableFacts = new ArrayList<>();

    /**
     * 用户已明确表达的偏好或约束。
     */
    @Builder.Default
    private List<String> userPreferences = new ArrayList<>();

    /**
     * 已经达成结论或已经解释清楚的点。
     */
    @Builder.Default
    private List<String> resolvedPoints = new ArrayList<>();

    /**
     * 仍然悬而未决、后续可能继续追问的问题。
     */
    @Builder.Default
    private List<String> pendingQuestions = new ArrayList<>();

    /**
     * 对后续检索仍有帮助的关键词、系统名、模块名等提示。
     */
    @Builder.Default
    private List<String> retrievalHints = new ArrayList<>();
}
