package org.javaup.ai.chatagent.model.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 长期会话摘要的结构化载体
 * @author: 阿星不是程序员
 **/

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor



/*
LLM 生成的结构化摘要，包含以下维度：
摘要不是一坨纯文本，而是结构化的 JSON。
这样下游可以按需取用：
准备阶段用 retrieval_hints 辅助检索，
规划阶段用 conversation_goal 理解用户意图，
回答阶段用 stable_facts 避免重复解释。
 */
public class ConversationSummaryPayload {

    private String summary;//对话整体摘要

    private String conversationGoal;//用户的对话目标

    @Builder.Default
    private List<String> stableFacts = new ArrayList<>();//已确认的稳定事实

    @Builder.Default
    private List<String> userPreferences = new ArrayList<>();//用户偏好

    @Builder.Default
    private List<String> resolvedPoints = new ArrayList<>();//已解决的问题

    @Builder.Default
    private List<String> pendingQuestions = new ArrayList<>();//待解决的问题

    @Builder.Default
    private List<String> retrievalHints = new ArrayList<>();//检索提示关键词


}
