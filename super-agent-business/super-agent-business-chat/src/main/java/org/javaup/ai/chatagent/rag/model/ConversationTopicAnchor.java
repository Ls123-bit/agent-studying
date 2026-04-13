package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话信息面向锚点。
 *
 * <p>它表达的是“当前在围绕主体问哪一类信息”，
 * 例如产品简介、核心特性、技术规格、现象、处理步骤等。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationTopicAnchor {

    /**
     * 当前主题文本。
     */
    private String anchorText;

    /**
     * 当前显式面向。
     */
    private String facet;

    /**
     * 当前信息需求。
     */
    private String informationNeed;

    public boolean isEmpty() {
        return (anchorText == null || anchorText.isBlank())
            && (facet == null || facet.isBlank())
            && (informationNeed == null || informationNeed.isBlank());
    }
}
