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
 * 文档父块实体。
 *
 * <p>这是 Parent-Child / Small-to-Big 结构里的父层持久化对象。
 * 它保存的是更稳定的大语义单元，后续回答阶段会优先回到这层取上下文，
 * 而不是继续依赖查询阶段临时拼邻块。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_document_parent_block")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentParentBlock extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 文档 id。
     */
    private Long documentId;

    /**
     * 索引任务 id。
     */
    private Long taskId;

    /**
     * 策略方案 id。
     */
    private Long planId;

    /**
     * 父块序号。
     */
    private Integer parentNo;

    /**
     * 内容来源。
     */
    private Integer sourceType;

    /**
     * 章节路径。
     */
    private String sectionPath;

    /**
     * 关联的结构节点 id。
     */
    private Long structureNodeId;

    /**
     * 关联的结构节点类型。
     */
    private Integer structureNodeType;

    /**
     * 结构节点稳定路径。
     */
    private String canonicalPath;

    /**
     * 列表/步骤项序号；章节型父块一般为空。
     */
    private Integer itemIndex;

    /**
     * 父块完整正文。
     */
    private String parentText;

    /**
     * 字符数。
     */
    private Integer charCount;

    /**
     * token 数。
     */
    private Integer tokenCount;

    /**
     * 父块内部 child 数量。
     */
    private Integer childCount;

    /**
     * 父块映射到的第一个 child 序号。
     */
    private Integer startChunkNo;

    /**
     * 父块映射到的最后一个 child 序号。
     */
    private Integer endChunkNo;
}
