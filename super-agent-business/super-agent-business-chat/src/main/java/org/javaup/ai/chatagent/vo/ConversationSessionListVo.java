package org.javaup.ai.chatagent.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.chatagent.model.ConversationSessionView;

import java.util.List;

/**
 * 会话列表出参。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSessionListVo {

    /**
     * 当前所有可见会话。
     */
    private List<ConversationSessionView> sessions;
}
