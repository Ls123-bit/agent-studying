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
 * 文档切块实体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_document_chunk")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentChunk extends BaseTableData {

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
     * 所属父块 id。
     *
     * <p>Child 负责召回，Parent 负责回答。
     * 因此每个 child chunk 都必须稳定挂到一个 parent block 下，
     * 后续检索命中后才能从 child 提升回 parent。</p>
     */
    private Long parentBlockId;

    /**
     * 切块序号。
     */
    private Integer chunkNo;

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
     * 列表/步骤项序号。
     */
    private Integer itemIndex;

    /**
     * 切块内容。
     */
    private String chunkText;

    /**
     * 字符数。
     */
    private Integer charCount;

    /**
     * token 数。
     */
    private Integer tokenCount;

    /**
     * 向量状态。
     */
    private Integer vectorStatus;

    /**
     * 向量库类型。
     */
    private Integer vectorStoreType;

    /**
     * 向量主键。
     */
    private String vectorId;
}
