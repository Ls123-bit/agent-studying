package org.javaup.ai.model;

public record ProductInfo(
    String productId,
    String productName,
    String price,
    int stock,
    String highlights
) {
}
