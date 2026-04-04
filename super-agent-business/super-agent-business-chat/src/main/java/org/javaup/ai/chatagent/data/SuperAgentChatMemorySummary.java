package org.javaup.ai.chatagent.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;

import java.util.Date;

/**
 * 会话长期记忆摘要快照实体。
 *
 * <p>这张表不保存逐轮原始问答，
 * 而是保存“已经压缩后的长期会话背景”，用于下一轮路由、改写和检索前置编排。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_chat_memory_summary")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentChatMemorySummary extends BaseTableData {

    /**
     * 主键 id。
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 所属业务会话编号。
     */
    @TableField("dialogue_code")
    private String conversationId;

    /**
     * 长期摘要已经覆盖到的最后一条 exchangeId。
     */
    @TableField("covered_exchange_id")
    private Long coveredExchangeId;

    /**
     * 长期摘要当前已经覆盖的轮次数。
     */
    @TableField("covered_exchange_count")
    private Integer coveredExchangeCount;

    /**
     * 摘要累计压缩次数。
     */
    @TableField("compression_count")
    private Integer compressionCount;

    /**
     * 摘要版本号。
     */
    @TableField("summary_version")
    private Integer summaryVersion;

    /**
     * 面向编排阶段直接使用的长期摘要文本。
     */
    @TableField("summary_text")
    private String summaryText;

    /**
     * 长期摘要的结构化 JSON 快照。
     */
    @TableField("summary_json")
    private String summaryJson;

    /**
     * 这份摘要覆盖的最后一条源轮次更新时间。
     */
    @TableField("last_source_edit_time")
    private Date lastSourceEditTime;
}
