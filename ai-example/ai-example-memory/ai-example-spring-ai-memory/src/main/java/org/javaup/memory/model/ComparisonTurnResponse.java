package org.javaup.memory.model;

public record ComparisonTurnResponse(
    int round,
    String question,
    String noMemoryAnswer,
    String slidingWindowAnswer,
    String summaryAnswer,
    String summarySnapshot,
    int summaryCompressionCount
) {
}
