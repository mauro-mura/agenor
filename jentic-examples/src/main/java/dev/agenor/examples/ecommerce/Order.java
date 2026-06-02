package dev.agenor.examples.ecommerce;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Order entity
 */
record Order(
    String orderId,
    String customerId,
    List<OrderItem> items,
    BigDecimal totalAmount,
    OrderStatus status,
    Instant createdAt,
    String paymentId,
    String shipmentId
) {
    public Order(String customerId, List<OrderItem> items) {
        this(
            UUID.randomUUID().toString(),
            customerId,
            new ArrayList<>(items),
            items.stream()
                .map(OrderItem::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add),
            OrderStatus.PENDING,
            Instant.now(),
            null,
            null
        );
    }

    public Order withStatus(OrderStatus newStatus) {
        return new Order(orderId, customerId, items, totalAmount, newStatus,
                        createdAt, paymentId, shipmentId);
    }

    public Order withPaymentId(String paymentId) {
        return new Order(orderId, customerId, items, totalAmount, status,
                        createdAt, paymentId, shipmentId);
    }

    public Order withShipmentId(String shipmentId) {
        return new Order(orderId, customerId, items, totalAmount, status,
                        createdAt, paymentId, shipmentId);
    }
}

record OrderItem(String productId, String productName, int quantity, BigDecimal price) {
    public BigDecimal subtotal() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }
}

enum OrderStatus {
    PENDING,
    VALIDATING,
    PAYMENT_PROCESSING,
    PREPARING,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    FAILED
}

/**
 * Validation results
 */
record ValidationResult(boolean valid, String reason) {
    public static ValidationResult success() {
        return new ValidationResult(true, null);
    }

    public static ValidationResult failure(String reason) {
        return new ValidationResult(false, reason);
    }
}
