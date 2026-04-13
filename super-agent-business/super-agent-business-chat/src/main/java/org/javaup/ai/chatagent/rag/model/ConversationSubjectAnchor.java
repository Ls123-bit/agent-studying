package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话主题对象锚点。
 *
 * <p>它表达的是“当前这轮围绕的核心对象/问题主体是什么”，
 * 例如产品名、故障主题、业务对象等。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSubjectAnchor {

    /**
     * 主体文本。
     */
    private String anchorText;

    /**
     * 锚点来源说明。
     */
    private String source;

    /**
     * 当前是否继承自上文。
     */
    private boolean inherited;

    public boolean isEmpty() {
        return anchorText == null || anchorText.isBlank();
    }
}
