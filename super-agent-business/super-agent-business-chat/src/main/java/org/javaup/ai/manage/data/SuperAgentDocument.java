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
     * 当前生效策略方案 id。
     */
    private Long currentPlanId;

    /**
     * 最近一次成功索引任务 id。
     */
    private Long lastIndexTaskId;
}
