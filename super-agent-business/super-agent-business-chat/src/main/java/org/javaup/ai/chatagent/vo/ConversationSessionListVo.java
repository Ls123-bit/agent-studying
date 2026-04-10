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
     * 当前页码。
     */
    private long pageNo;

    /**
     * 每页条数。
     */
    private long pageSize;

    /**
     * 总记录数。
     */
    private long totalSize;

    /**
     * 总页数。
     */
    private long totalPages;

    /**
     * 当前所有可见会话。
     */
    private List<ConversationSessionView> sessions;
}
