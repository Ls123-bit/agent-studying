package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 会话编号项锚点
 * @author: 阿星不是程序员
 **/
/**
 * 会话编号项锚点。
 *
 * <p>它表达的是“当前是否在追问某个步骤/列表项”。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationItemAnchor {

    /**
     * 项序号。
     */
    private Integer itemIndex;

    /**
     * 项文本。
     */
    private String itemText;

    /**
     * 对应结构节点主键。
     */
    private Long structureNodeId;

    /**
     * 对应结构节点路径。
     */
    private String canonicalPath;

    public boolean isEmpty() {
        return itemIndex == null
            && (itemText == null || itemText.isBlank())
            && structureNodeId == null
            && (canonicalPath == null || canonicalPath.isBlank());
    }
}
