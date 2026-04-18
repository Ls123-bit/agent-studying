package org.javaup.ai.chatagent.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 数据实体
 * @author: 阿星不是程序员
 **/
/**
 * Spring AI Alibaba Graph checkpoint 表实体。
 *
 * <p>这张表保存的是 ReactAgent 在某个节点上的运行态快照，
 * 比如当前节点、下一节点和序列化后的 state_data。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("GRAPH_CHECKPOINT")
public class GraphCheckpoint {

    /**
     * Checkpoint 主键。
     */
    @TableId(value = "checkpoint_id", type = IdType.INPUT)
    private String checkpointId;

    /**
     * 关联的 Graph 线程 ID。
     */
    private String threadId;

    /**
     * 当前节点 ID。
     */
    private String nodeId;

    /**
     * 下一节点 ID。
     */
    private String nextNodeId;

    /**
     * 序列化后的状态 JSON。
     */
    private String stateData;

    /**
     * 保存时间。
     */
    private LocalDateTime savedAt;
}
