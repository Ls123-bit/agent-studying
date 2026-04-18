package org.javaup.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 数据实体
 * @author: 阿星不是程序员
 **/
/**
 * 文档结构节点实体。
 *
 * <p>它表达的是“解析阶段提取出的文档结构树”，
 * 是后续多轮导航、结构化检索和章节/步骤定位的底座。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_document_structure_node")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentStructureNode extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 所属文档 id。
     */
    private Long documentId;

    /**
     * 生成该结构树的解析任务 id。
     */
    private Long parseTaskId;

    /**
     * 当前文档内的稳定顺序号。
     */
    private Integer nodeNo;

    /**
     * 节点类型。
     */
    private Integer nodeType;

    /**
     * 父节点 id。
     */
    private Long parentNodeId;

    /**
     * 上一个同级节点 id。
     */
    private Long prevSiblingNodeId;

    /**
     * 下一个同级节点 id。
     */
    private Long nextSiblingNodeId;

    /**
     * 层级深度。
     */
    private Integer depth;

    /**
     * 结构编码。
     */
    private String nodeCode;

    /**
     * 节点标题。
     */
    private String title;

    /**
     * 供锚点和导航使用的短锚文本。
     */
    private String anchorText;

    /**
     * 节点稳定路径。
     */
    private String canonicalPath;

    /**
     * 面向现有系统兼容的章节路径文本。
     */
    private String sectionPath;

    /**
     * 节点正文。
     */
    private String contentText;

    /**
     * 列表项/步骤项序号。
     */
    private Integer itemIndex;
}
