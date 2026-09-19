package org.javaup.ai.model;

public record RefundResult(
    String orderId,
    boolean accepted,
    String refundNo,
    String message
) {
}
