package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排阶段使用的结构化历史要点。
 *
 * <p>相比“整段拼接后的历史文本”，
 * 这份对象更适合被路由、改写和检索规划复用。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoryPlanningContext {

    /**
     * 会话长期目标。
     */
    private String conversationGoal;

    /**
     * 已确认事实。
     */
    @Builder.Default
    private List<String> stableFacts = new ArrayList<>();

    /**
     * 待跟进问题。
     */
    @Builder.Default
    private List<String> pendingQuestions = new ArrayList<>();

    /**
     * 对检索仍有帮助的提示词。
     */
    @Builder.Default
    private List<String> retrievalHints = new ArrayList<>();

    /**
     * 对短追问有效的上下文提示。
     *
     * <p>这类提示会单独下沉到检索请求里做轻量 boost，
     * 不会直接并入主查询文本。</p>
     */
    @Builder.Default
    private List<String> queryContextHints = new ArrayList<>();
}
