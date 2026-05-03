package ru.batoyan.vkr.notification.history.writer.history;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

public record ClickHouseDeliveryHistoryRecord(
        long outboxId,
        String aggregateType,
        String aggregateId,
        String eventType,
        OffsetDateTime createdAt,
        OffsetDateTime occurredAt,
        OffsetDateTime ingestedAt,
        String clientId,
        String eventId,
        String dispatchId,
        String recipientId,
        String deliveryId,
        String destination,
        String channel,
        String provider,
        String status,
        String previousStatus,
        String reasonCode,
        String reasonMessage,
        int attemptNo,
        int maxAttempts,
        boolean isFinal,
        OffsetDateTime nextAttemptAt,
        OffsetDateTime scheduledAt,
        OffsetDateTime sentAt,
        OffsetDateTime failedAt,
        Long latencyMs,
        String providerMessageId,
        String templateId,
        int templateVersion,
        int priority,
        String correlationId,
        String idempotencyKey,
        String kafkaTopic,
        int kafkaPartition,
        long kafkaOffset,
        String payloadHash,
        String payloadJson,
        String headersJson,
        String metadataJson
) {

    public static ClickHouseDeliveryHistoryRecord fromEvent(DeliveryStatusEvent event, ObjectMapper objectMapper) {
        var payload = event.payload();
        var headers = event.headers();
        var payloadJson = writeJson(objectMapper, payload);
        var headersJson = writeJson(objectMapper, headers);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("kafka_topic", event.kafkaTopic());
        metadata.put("kafka_partition", event.kafkaPartition());
        metadata.put("kafka_offset", event.kafkaOffset());
        metadata.put("message_id", firstNonBlank(null, headers, "message_id", "messageId"));
        metadata.put("event_type", event.eventType());

        var occurredAt = parseOffsetDateTime(firstNonBlank(payload, headers, "occurred_at"));
        var nextAttemptAt = parseOffsetDateTime(firstNonBlank(payload, headers, "next_attempt_at"));
        var scheduledAt = parseOffsetDateTime(firstNonBlank(payload, headers, "scheduled_at"));
        var sentAt = parseOffsetDateTime(firstNonBlank(payload, headers, "sent_at"));
        var failedAt = parseOffsetDateTime(firstNonBlank(payload, headers, "failed_at"));
        var status = firstNonBlank(payload, headers, "status");

        return new ClickHouseDeliveryHistoryRecord(
                event.outboxId(),
                safe(event.aggregateType()),
                fallback(firstNonBlank(payload, headers, "delivery_id"), event.aggregateId()),
                safe(event.eventType()),
                event.createdAt().withOffsetSameInstant(ZoneOffset.UTC),
                occurredAt,
                OffsetDateTime.now(ZoneOffset.UTC),
                firstNonBlank(payload, headers, "client_id"),
                firstNonBlank(payload, headers, "event_id"),
                firstNonBlank(payload, headers, "dispatch_id"),
                firstNonBlank(payload, headers, "recipient_id"),
                safe(event.aggregateId()),
                firstNonBlank(payload, headers, "email", "phone", "destination"),
                firstNonBlank(payload, headers, "channel"),
                firstNonBlank(payload, headers, "provider"),
                status,
                firstNonBlank(payload, headers, "previous_status"),
                firstNonBlank(payload, headers, "reason_code", "error_code"),
                firstNonBlank(payload, headers, "error_message", "reason_message"),
                parseInt(firstNonBlank(payload, headers, "attempt_no")),
                parseInt(firstNonBlank(payload, headers, "max_attempts")),
                isFinalStatus(status),
                nextAttemptAt,
                scheduledAt,
                sentAt,
                failedAt,
                parseLongOrNull(firstNonBlank(payload, headers, "latency_ms")),
                firstNonBlank(payload, headers, "provider_message_id"),
                firstNonBlank(payload, headers, "template_id"),
                parseInt(firstNonBlank(payload, headers, "template_version")),
                parseInt(firstNonBlank(payload, headers, "priority")),
                firstNonBlank(payload, headers, "correlation_id", "correlationId"),
                firstNonBlank(payload, headers, "idempotency_key"),
                safe(event.kafkaTopic()),
                event.kafkaPartition(),
                event.kafkaOffset(),
                sha256(payloadJson),
                payloadJson,
                headersJson,
                writeJson(objectMapper, metadata)
        );
    }

    private static String writeJson(ObjectMapper objectMapper, Map<String, ?> source) {
        try {
            return objectMapper.writeValueAsString(source == null ? Map.of() : source);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize history payload", ex);
        }
    }

    private static String firstNonBlank(Map<String, Object> payload, Map<String, Object> headers, String... keys) {
        for (var key : keys) {
            var payloadValue = read(payload, key);
            if (!payloadValue.isBlank()) {
                return payloadValue;
            }
            var headerValue = read(headers, key);
            if (!headerValue.isBlank()) {
                return headerValue;
            }
        }
        return "";
    }

    private static String read(Map<String, Object> source, String key) {
        if (source == null) {
            return "";
        }
        var value = source.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static OffsetDateTime parseOffsetDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return Integer.parseInt(value);
    }

    private static Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Long.parseLong(value);
    }

    private static boolean isFinalStatus(String status) {
        return status.endsWith("_SENT") || status.endsWith("_FAILED") || status.endsWith("_SKIPPED");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? safe(fallback) : value;
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            var builder = new StringBuilder(digest.length * 2);
            for (var b : digest) {
                builder.append(String.format("%02x", b & 0xff));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute payload hash", ex);
        }
    }
}
