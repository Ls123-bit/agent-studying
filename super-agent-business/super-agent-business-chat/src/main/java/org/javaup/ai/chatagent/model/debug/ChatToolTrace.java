package org.javaup.ai.chatagent.model.debug;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单次工具调用观测快照。
 *
 * <p>它面向后台观测页，
 * 用来回答“这一轮到底调了什么工具、传了什么关键输入、结果怎么样”。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatToolTrace {

    /**
     * 工具名称。
     */
    private String toolName;

    /**
     * 当前状态：RUNNING / COMPLETED / FAILED。
     */
    private String status;

    /**
     * 工具输入摘要。
     */
    private String inputSummary;

    /**
     * 工具真正执行时使用的有效输入。
     */
    private String effectiveInput;

    /**
     * 工具结果摘要。
     */
    private String outputSummary;

    /**
     * 工具错误信息。
     */
    private String errorMessage;

    /**
     * 命中的来源数量。
     */
    private Integer referenceCount;

    /**
     * 工具内部选择的主题或通道。
     */
    private String topic;

    /**
     * 本次工具调用耗时，毫秒。
     */
    private Long durationMs;
}
