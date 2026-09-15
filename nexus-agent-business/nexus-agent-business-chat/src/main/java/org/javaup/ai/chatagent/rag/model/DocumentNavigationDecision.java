package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 文档问答路由结果
 * @author: 阿星不是程序员
 **/
/*
文档问题路由的决策结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentNavigationDecision {

    private DocumentNavigationAction navigationAction;//导航动作（邻接查找/子节点下钻/条目引用/全新话题）

    private ExecutionMode executionMode;//确定的执行模式

    private ConversationStructureAnchor structureAnchor;//定位到的章节锚点

    private ConversationItemAnchor itemAnchor;//定位到的条目锚点

    private RetrievalQuestionPlan retrievalPlan;//检索用的问题和子问题

    private String summaryText;//路由决策的可读摘要

    @Builder.Default
    private List<String> queryContextHints = new ArrayList<>();//检索关键词提示

    @Builder.Default
    private List<String> softSectionHints = new ArrayList<>();//软章节提示（辅助检索）
}
