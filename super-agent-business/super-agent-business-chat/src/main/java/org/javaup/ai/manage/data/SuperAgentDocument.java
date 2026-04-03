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
 * 文档主表实体。
 *
 * <p>该表负责记录文档接入链路中的核心静态信息和主状态，
 * 比如文件存储位置、解析状态、策略状态、索引状态等。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_document")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocument extends BaseTableData {

    /**
     * 主键 id。
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 文档名称。
     */
    private String documentName;

    /**
     * 原始文件名。
     */
    private String originalFileName;

    /**
     * 文件类型。
     */
    private Integer fileType;

    /**
     * MIME 类型。
     */
    private String mimeType;

    /**
     * 文件大小，单位 byte。
     */
    private Long fileSize;

    /**
     * 文件存储类型。
     */
    private Integer storageType;

    /**
     * MinIO bucket 名称。
     */
    private String bucketName;

    /**
     * MinIO 对象名称。
     */
    private String objectName;

    /**
     * 文件对象访问地址。
     */
    private String objectUrl;

    /**
     * 解析状态。
     */
    private Integer parseStatus;

    /**
     * 策略状态。
     */
    private Integer strategyStatus;

    /**
     * 索引状态。
     */
    private Integer indexStatus;

    /**
     * 解析后的字符数。
     */
    private Integer charCount;

    /**
     * 粗略估算的 token 数。
     */
    private Integer tokenCount;

    /**
     * 文档结构化程度。
     */
    private Integer structureLevel;

    /**
     * 文档内容质量等级。
     */
    private Integer contentQualityLevel;

    /**
     * 解析文本在 MinIO 中的对象路径。
     */
    private String parseTextPath;

    /**
     * 解析失败原因。
     */
    private String parseErrorMsg;

    /**
     * 业务知识域编码。
     *
     * <p>例如：oa / crm / finance。
     * 聊天侧做知识域收缩、歧义澄清和后续元数据过滤时，会优先依赖这个字段。</p>
     */
    private String knowledgeScopeCode;

    /**
     * 业务知识域名称。
     *
     * <p>例如：OA系统 / CRM系统 / 财务系统。</p>
     */
    private String knowledgeScopeName;

    /**
     * 业务分类。
     *
     * <p>这是一个比 scope 更细的标签，适合把同一系统下的文档再分成流程、规则、操作手册等类别。</p>
     */
    private String businessCategory;

    /**
     * 标签快照。
     *
     * <p>当前为了保持实现轻量，这里直接使用逗号分隔字符串保存，
     * 便于后续在检索规划阶段快速做命中判断。</p>
     */
    private String documentTags;

    /**
     * 当前生效策略方案 id。
     */
    private Long currentPlanId;

    /**
     * 最近一次成功索引任务 id。
     */
    private Long lastIndexTaskId;
}
