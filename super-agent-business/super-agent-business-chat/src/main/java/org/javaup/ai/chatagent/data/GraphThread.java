package org.javaup.ai.chatagent.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 数据实体
 * @author: 阿星不是程序员
 **/
/**
 * Spring AI Alibaba Graph 线程表实体。
 *
 * <p>这张表不是业务会话表，而是 ReactAgent checkpoint 体系内部使用的线程表。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("GRAPH_THREAD")
public class GraphThread {

    /**
     * Graph 内部线程主键。
     */
    @TableId(value = "thread_id", type = IdType.INPUT)
    private String threadId;

    /**
     * 业务线程名，当前项目里通常就是 conversationId。
     */
    private String threadName;

    /**
     * 是否已被框架 release。
     *
     * <p>注意 release 只是标记线程已释放，不等于物理删除整条线程和对应 checkpoint。</p>
     */
    @TableField("is_released")
    private Boolean released;
}
