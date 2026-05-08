package ru.batoyan.vkr.notification.scheduler.delivery.scheduling;

import java.time.OffsetDateTime;
import java.util.Map;

public record KafkaEnvelope(
        long outboxId,
        String aggregateType,
        String aggregateId,
        String eventType,
        Map<String, Object> payload,
        Map<String, Object> headers,
        OffsetDateTime createdAt
) {
}
