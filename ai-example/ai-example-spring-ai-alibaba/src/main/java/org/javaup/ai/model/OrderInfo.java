package org.javaup.ai.model;

public record OrderInfo(
    String orderId,
    String productName,
    String status,
    String logisticsNo,
    String expectedDelivery,
    boolean canRefund
) {
}
