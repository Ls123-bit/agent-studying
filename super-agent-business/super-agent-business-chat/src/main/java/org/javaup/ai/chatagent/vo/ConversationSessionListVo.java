package org.javaup.ai.chatagent.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.chatagent.model.ConversationSessionView;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 视图对象
 * @author: 阿星不是程序员
 **/
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
