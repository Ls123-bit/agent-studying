package org.javaup.ai.chatagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class StageBenchmarkView {

    private String stageCode;
    private String executionMode;
    private Long p50DurationMs;
    private Long p90DurationMs;
    private Long p99DurationMs;
    private Long avgDurationMs;
    private Long maxDurationMs;
    private Long minDurationMs;
    private int sampleCount;
}
