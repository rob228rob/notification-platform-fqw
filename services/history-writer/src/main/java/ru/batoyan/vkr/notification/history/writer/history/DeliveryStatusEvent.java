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
        OffsetDateTime createdAt,
        String kafkaTopic,
        int kafkaPartition,
        long kafkaOffset
) {

    public DeliveryStatusEvent withKafkaMetadata(String kafkaTopic, int kafkaPartition, long kafkaOffset) {
        return new DeliveryStatusEvent(
                outboxId,
                aggregateType,
                aggregateId,
                eventType,
                payload,
                headers,
                createdAt,
                kafkaTopic,
                kafkaPartition,
                kafkaOffset
        );
    }
}
