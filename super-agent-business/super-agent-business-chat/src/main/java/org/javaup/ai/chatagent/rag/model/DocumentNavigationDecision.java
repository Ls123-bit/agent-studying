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
/**
 * 文档问答路由结果。
 *
 * <p>重构后它只表达两类信息：</p>
 * <p>1. 当前问题最终走哪条执行路径。</p>
 * <p>2. 如果走图查询，目标章节 / item 是什么。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentNavigationDecision {

    private DocumentNavigationAction navigationAction;

    private ExecutionMode executionMode;

    private ConversationStructureAnchor structureAnchor;

    private ConversationItemAnchor itemAnchor;

    private RetrievalQuestionPlan retrievalPlan;

    private String summaryText;

    @Builder.Default
    private List<String> queryContextHints = new ArrayList<>();

    @Builder.Default
    private List<String> softSectionHints = new ArrayList<>();
}
