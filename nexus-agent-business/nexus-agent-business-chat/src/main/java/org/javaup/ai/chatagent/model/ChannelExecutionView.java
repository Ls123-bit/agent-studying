package org.javaup.ai.chatagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChannelExecutionView {

    private Long id; //唯一标识，由 traceRecorder 分配
    private String traceId;//关联到本轮对话的 trace ID
    private int subQuestionIndex;//子问题序号
    private String subQuestion;//子问题文本
    private String channelType;//通道类型（vector / keyword）
    private int executionState;//执行状态，1=成功
    private Instant startTime;//通道执行开始时间
    private Instant endTime;//通道执行结束时间
    private Long durationMs;//执行耗时（毫秒）
    private int recalledCount;//原始召回数量
    private int acceptedCount;//闸门后采纳数量
    private int finalSelectedCount;//最终选择数量
    private BigDecimal avgScore;//平均分数
    private BigDecimal maxScore;//最高分数
    private BigDecimal minScore;//最高分数
    private String errorMessage;//执行失败时的错误信息
    private Instant createTime;//创建时间
}

/*
观测数据的用途
这些通道级观测数据主要用于两个场景：
1.调试面板：开发者可以在前端看到每个通道的"漏斗"——从召回到闸门到最终选择，哪一步损耗最大
2.质量监控：通过分数统计可以发现通道质量退化，比如某天向量通道的平均分突然下降，可能是 embedding 模型出了问题
 */