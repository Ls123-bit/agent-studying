package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoryPlanningContext {

    private String conversationGoal;    //会话目标   帮助理解"当前会话整体在推进什么事情"

    @Builder.Default
    private List<String> stableFacts = new ArrayList<>();//已确认事实    帮助系统知道哪些信息已经确定

    @Builder.Default
    private List<String> pendingQuestions = new ArrayList<>();//待跟进问题    帮助系统知道哪些问题还在延续

    @Builder.Default
    private List<String> retrievalHints = new ArrayList<>();//检索提示  给后续检索提供主题关键词

    @Builder.Default
    private List<String> queryContextHints = new ArrayList<>();//查询上下文提示 给查询改写提供上下文暗示
}
