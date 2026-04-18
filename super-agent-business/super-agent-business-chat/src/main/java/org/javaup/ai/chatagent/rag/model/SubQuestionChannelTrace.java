package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 单个子问题在某个检索通道上的执行痕迹
 * @author: 阿星不是程序员
 **/
/**
 * 单个子问题在某个检索通道上的执行痕迹。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubQuestionChannelTrace {

    private String channelName;

    /**
     * 通道原始召回数。
     */
    private int recalledCount;

    /**
     * 经过相关性闸门后的保留数。
     */
    private int acceptedCount;
}
