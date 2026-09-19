package org.javaup.ai.model;

public record OrderSummary(
    String orderId,
    String status,
    boolean canRefund,
    String nextAction
) {
}
