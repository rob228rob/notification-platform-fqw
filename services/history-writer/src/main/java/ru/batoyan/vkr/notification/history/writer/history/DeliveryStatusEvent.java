package ru.batoyan.vkr.notification.history.writer.history;

import java.time.OffsetDateTime;
import java.util.Map;

public record DeliveryStatusEvent(
        long outboxId,
        String aggregateType,
        String aggregateId,
        String eventType,
        Map<String, Object> payload,
        Map<String, Object> headers,
        OffsetDateTime createdAt
) {
}
