package org.javaup.ai.manage.model.es;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 文档导航索引记录
 * @author: 阿星不是程序员
 **/
/**
 * 文档导航索引记录。
 *
 * <p>这个索引的粒度是结构节点，不是正文 chunk。它服务于导航层的章节定位，
 * 让中文主题匹配交给 ES/IK 分词，而不是在内存里做脆弱的 contains 判断。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentNavigationIndexRecord {

    private Long nodeId;

    private Long documentId;

    private Long parseTaskId;

    /**
     * SECTION / STEP / LIST_ITEM。
     */
    private String nodeType;

    private String nodeCode;

    private Integer nodeNo;

    private Integer depth;

    private Long parentNodeId;

    private String title;

    private String anchorText;

    private String sectionPath;

    private String canonicalPath;

    private String contentText;

    private Integer itemIndex;
}
