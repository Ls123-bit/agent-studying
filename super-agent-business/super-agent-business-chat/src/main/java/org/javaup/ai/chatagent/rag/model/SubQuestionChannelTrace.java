package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
