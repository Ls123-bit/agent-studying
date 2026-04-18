package org.javaup.ai.chatagent.model.debug;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 单次模型调用的使用量轨迹
 * @author: 阿星不是程序员
 **/
/**
 * 单次模型调用的使用量轨迹。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatModelUsageTrace {

    /**
     * 业务阶段名称，例如 intent / rewrite / summary。
     */
    private String stageName;

    /**
     * 模型提供方。
     */
    private String provider;

    /**
     * 模型名称。
     */
    private String model;

    /**
     * 输入 token 数。
     */
    private Integer promptTokens;

    /**
     * 输出 token 数。
     */
    private Integer completionTokens;

    /**
     * 总 token 数。
     */
    private Integer totalTokens;

    /**
     * 估算成本。
     */
    private Double estimatedCost;

    /**
     * 耗时，毫秒。
     */
    private Long durationMs;

    /**
     * 状态：COMPLETED / FAILED。
     */
    private String status;
}
