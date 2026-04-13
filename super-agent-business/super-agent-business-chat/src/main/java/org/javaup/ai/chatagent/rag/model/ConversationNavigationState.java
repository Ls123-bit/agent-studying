package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话导航状态。
 *
 * <p>它把“主体 / 主题 / 结构 / 编号项”四层锚点收敛成统一对象，
 * 作为当前这一轮检索规划和调试观测的共享状态。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationNavigationState {

    /**
     * 当前轮与上文关系。
     */
    private ConversationIntentRelationType relationType;

    /**
     * 当前轮检索模式。
     */
    private ConversationRetrievalMode retrievalMode;

    /**
     * 主体锚点。
     */
    private ConversationSubjectAnchor subjectAnchor;

    /**
     * 主题锚点。
     */
    private ConversationTopicAnchor topicAnchor;

    /**
     * 结构锚点。
     */
    private ConversationStructureAnchor structureAnchor;

    /**
     * 编号项锚点。
     */
    private ConversationItemAnchor itemAnchor;

    /**
     * 导航摘要文本。
     */
    private String summaryText;

    public boolean isEmpty() {
        return (subjectAnchor == null || subjectAnchor.isEmpty())
            && (topicAnchor == null || topicAnchor.isEmpty())
            && (structureAnchor == null || structureAnchor.isEmpty())
            && (itemAnchor == null || itemAnchor.isEmpty());
    }
}
