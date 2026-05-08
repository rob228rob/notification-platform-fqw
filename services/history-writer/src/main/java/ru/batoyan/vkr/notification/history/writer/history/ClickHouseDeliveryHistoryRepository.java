package ru.batoyan.vkr.notification.history.writer.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.batoyan.vkr.notification.history.writer.config.HistoryClickHouseProperties;
import ru.notification.common.proto.v1.Channel;
import ru.notification.history.proto.v1.ChannelDeliverySummary;
import ru.notification.history.proto.v1.DeliveryHistoryPayload;
import ru.notification.history.proto.v1.DeliveryStatus;
import ru.notification.history.proto.v1.DeliveryStatusKafkaEvent;
import ru.notification.history.proto.v1.RecipientDeliverySummary;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "history.storage.type", havingValue = "clickhouse", matchIfMissing = true)
public class ClickHouseDeliveryHistoryRepository implements DeliveryHistoryStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final HistoryClickHouseProperties properties;

    @Override
    public boolean save(DeliveryStatusEvent event) {
        var record = ClickHouseDeliveryHistoryRecord.fromEvent(event, objectMapper);
        var sql = """
                insert into %s (
                    event_date, occurred_at, ingested_at, created_at,
                    client_id, event_id, dispatch_id, recipient_id, aggregate_id, channel, provider, delivery_id,
                    status, previous_status, reason_code, reason_message, attempt_no, max_attempts, is_final,
                    next_attempt_at, scheduled_at, sent_at, failed_at, latency_ms, provider_message_id,
                    template_id, template_version, priority, correlation_id, idempotency_key,
                    kafka_topic, kafka_partition, kafka_offset, aggregate_type, event_type, outbox_id,
                    destination, payload_hash, payload_json, headers_json, metadata_json
                ) values (
                    :event_date, :occurred_at, :ingested_at, :created_at,
                    :client_id, :event_id, :dispatch_id, :recipient_id, :aggregate_id, :channel, :provider, :delivery_id,
                    :status, :previous_status, :reason_code, :reason_message, :attempt_no, :max_attempts, :is_final,
                    :next_attempt_at, :scheduled_at, :sent_at, :failed_at, :latency_ms, :provider_message_id,
                    :template_id, :template_version, :priority, :correlation_id, :idempotency_key,
                    :kafka_topic, :kafka_partition, :kafka_offset, :aggregate_type, :event_type, :outbox_id,
                    :destination, :payload_hash, :payload_json, :headers_json, :metadata_json
                )
                """.formatted(tableName());

        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("event_date", coalesce(record.occurredAt(), record.createdAt()).toLocalDate())
                .addValue("occurred_at", record.occurredAt())
                .addValue("ingested_at", record.ingestedAt())
                .addValue("created_at", record.createdAt())
                .addValue("client_id", record.clientId())
                .addValue("event_id", record.eventId())
                .addValue("dispatch_id", record.dispatchId())
                .addValue("recipient_id", record.recipientId())
                .addValue("aggregate_id", record.aggregateId())
                .addValue("channel", record.channel())
                .addValue("provider", record.provider())
                .addValue("delivery_id", record.deliveryId())
                .addValue("status", record.status())
                .addValue("previous_status", record.previousStatus())
                .addValue("reason_code", record.reasonCode())
                .addValue("reason_message", record.reasonMessage())
                .addValue("attempt_no", record.attemptNo())
                .addValue("max_attempts", record.maxAttempts())
                .addValue("is_final", record.isFinal() ? 1 : 0)
                .addValue("next_attempt_at", record.nextAttemptAt())
                .addValue("scheduled_at", record.scheduledAt())
                .addValue("sent_at", record.sentAt())
                .addValue("failed_at", record.failedAt())
                .addValue("latency_ms", record.latencyMs())
                .addValue("provider_message_id", record.providerMessageId())
                .addValue("template_id", record.templateId())
                .addValue("template_version", record.templateVersion())
                .addValue("priority", record.priority())
                .addValue("correlation_id", record.correlationId())
                .addValue("idempotency_key", record.idempotencyKey())
                .addValue("kafka_topic", record.kafkaTopic())
                .addValue("kafka_partition", record.kafkaPartition())
                .addValue("kafka_offset", record.kafkaOffset())
                .addValue("aggregate_type", record.aggregateType())
                .addValue("event_type", record.eventType())
                .addValue("outbox_id", record.outboxId())
                .addValue("destination", record.destination())
                .addValue("payload_hash", record.payloadHash())
                .addValue("payload_json", record.payloadJson())
                .addValue("headers_json", record.headersJson())
                .addValue("metadata_json", record.metadataJson())) > 0;
    }

    @Override
    public long countByClientId(String clientId) {
        var total = jdbc.queryForObject("""
                select count()
                from %s
                where client_id = :client_id
                """.formatted(tableName()), Map.of("client_id", clientId), Long.class);
        return total == null ? 0 : total;
    }

    @Override
    public List<DeliveryStatusKafkaEvent> listByClientId(String clientId, int page, int size) {
        return jdbc.query("""
                select *
                from %s
                where client_id = :client_id
                order by coalesce(occurred_at, created_at) desc, ingested_at desc, outbox_id desc
                limit :limit offset :offset
                """.formatted(tableName()), new MapSqlParameterSource()
                .addValue("client_id", clientId)
                .addValue("limit", size)
                .addValue("offset", page * size), (rs, rowNum) -> toKafkaEvent(rs));
    }

    @Override
    public RecipientDeliverySummary getRecipientSummary(String recipientId, int lookbackHours) {
        return getRecipientSummaries(List.of(recipientId), lookbackHours).getOrDefault(
                recipientId,
                emptySummary(recipientId, lookbackHours)
        );
    }

    @Override
    public Map<String, RecipientDeliverySummary> getRecipientSummaries(List<String> recipientIds, int lookbackHours) {
        if (recipientIds == null || recipientIds.isEmpty()) {
            return Collections.emptyMap();
        }

        var sanitizedRecipientIds = recipientIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (sanitizedRecipientIds.isEmpty()) {
            return Collections.emptyMap();
        }

        var windowTo = OffsetDateTime.now(ZoneOffset.UTC);
        var windowFrom = windowTo.minusHours(Math.max(lookbackHours, 1));

        var rows = jdbc.query("""
                select recipient_id,
                       channel,
                       countIf(status in ('MAIL_DELIVERY_STATUS_SENT', 'SMS_DELIVERY_STATUS_SENT')) as successful_count,
                       countIf(status in (
                           'MAIL_DELIVERY_STATUS_FAILED', 'MAIL_DELIVERY_STATUS_SKIPPED', 'MAIL_DELIVERY_STATUS_CANCELED',
                           'SMS_DELIVERY_STATUS_FAILED', 'SMS_DELIVERY_STATUS_SKIPPED', 'SMS_DELIVERY_STATUS_CANCELED'
                       )) as unsuccessful_count
                from %s
                where recipient_id in (:recipient_ids)
                  and coalesce(occurred_at, created_at) >= :window_from
                  and coalesce(occurred_at, created_at) <= :window_to
                group by recipient_id, channel
                order by recipient_id, channel
                """.formatted(tableName()), new MapSqlParameterSource()
                .addValue("recipient_ids", sanitizedRecipientIds)
                .addValue("window_from", windowFrom)
                .addValue("window_to", windowTo), (rs, rowNum) -> new ChannelSummaryRow(
                rs.getString("recipient_id"),
                toChannel(rs.getString("channel")),
                rs.getInt("successful_count"),
                rs.getInt("unsuccessful_count")
        ));

        var summaries = new LinkedHashMap<String, RecipientDeliverySummary.Builder>();
        for (var recipientId : sanitizedRecipientIds) {
            summaries.put(recipientId, RecipientDeliverySummary.newBuilder()
                    .setRecipientId(recipientId)
                    .setWindowFrom(toTs(windowFrom.toInstant()))
                    .setWindowTo(toTs(windowTo.toInstant())));
        }

        for (var row : rows) {
            var builder = summaries.get(row.recipientId());
            if (builder == null) {
                continue;
            }
            var total = row.successfulCount() + row.unsuccessfulCount();
            builder.addChannels(ChannelDeliverySummary.newBuilder()
                    .setChannel(row.channel())
                    .setSuccessfulCount(row.successfulCount())
                    .setUnsuccessfulCount(row.unsuccessfulCount())
                    .setTotalCount(total)
                    .build());
            builder.setSuccessfulCount(builder.getSuccessfulCount() + row.successfulCount());
            builder.setUnsuccessfulCount(builder.getUnsuccessfulCount() + row.unsuccessfulCount());
            builder.setTotalCount(builder.getTotalCount() + total);
        }

        var result = new LinkedHashMap<String, RecipientDeliverySummary>(summaries.size());
        summaries.forEach((recipientId, builder) -> result.put(recipientId, builder.build()));
        return result;
    }

    private String tableName() {
        return properties.getDatabase() + "." + properties.getTable();
    }

    private DeliveryStatusKafkaEvent toKafkaEvent(ResultSet rs) throws SQLException {
        var payloadBuilder = DeliveryHistoryPayload.newBuilder()
                .setDispatchId(defaultString(rs.getString("dispatch_id")))
                .setEventId(defaultString(rs.getString("event_id")))
                .setClientId(defaultString(rs.getString("client_id")))
                .setRecipientId(defaultString(rs.getString("recipient_id")))
                .setEmail(defaultString(rs.getString("destination")))
                .setChannel(toChannel(rs.getString("channel")))
                .setStatus(toStatus(rs.getString("status")))
                .setTemplateId(defaultString(rs.getString("template_id")))
                .setTemplateVersion(rs.getInt("template_version"))
                .setIdempotencyKey(defaultString(rs.getString("idempotency_key")))
                .setAttemptNo(rs.getInt("attempt_no"))
                .setErrorMessage(defaultString(rs.getString("reason_message")));

        var nextAttemptAt = rs.getObject("next_attempt_at", OffsetDateTime.class);
        if (nextAttemptAt != null) {
            payloadBuilder.setNextAttemptAt(toTs(nextAttemptAt.toInstant()));
        }
        var occurredAt = rs.getObject("occurred_at", OffsetDateTime.class);
        if (occurredAt != null) {
            payloadBuilder.setOccurredAt(toTs(occurredAt.toInstant()));
        }

        return DeliveryStatusKafkaEvent.newBuilder()
                .setOutboxId(rs.getLong("outbox_id"))
                .setAggregateType(defaultString(rs.getString("aggregate_type")))
                .setAggregateId(defaultString(rs.getString("aggregate_id")))
                .setEventType(defaultString(rs.getString("event_type")))
                .setPayload(payloadBuilder)
                .putAllHeaders(readStringMap(rs.getString("headers_json")))
                .setCreatedAt(toTs(rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readStringMap(String json) {
        try {
            if (json == null || json.isBlank()) {
                return Collections.emptyMap();
            }
            var raw = (Map<String, Object>) objectMapper.readValue(json, Map.class);
            var result = new LinkedHashMap<String, String>(raw.size());
            raw.forEach((k, v) -> result.put(k, v == null ? "" : String.valueOf(v)));
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse headers json", ex);
        }
    }

    private RecipientDeliverySummary emptySummary(String recipientId, int lookbackHours) {
        var windowTo = OffsetDateTime.now(ZoneOffset.UTC);
        var windowFrom = windowTo.minusHours(Math.max(lookbackHours, 1));
        return RecipientDeliverySummary.newBuilder()
                .setRecipientId(recipientId)
                .setWindowFrom(toTs(windowFrom.toInstant()))
                .setWindowTo(toTs(windowTo.toInstant()))
                .build();
    }

    private static OffsetDateTime coalesce(OffsetDateTime first, OffsetDateTime second) {
        return first != null ? first : second;
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    private static DeliveryStatus toStatus(String dbValue) {
        return switch (dbValue) {
            case "MAIL_DELIVERY_STATUS_PENDING", "SMS_DELIVERY_STATUS_PENDING" -> DeliveryStatus.DELIVERY_STATUS_PENDING;
            case "MAIL_DELIVERY_STATUS_SENDING", "SMS_DELIVERY_STATUS_SENDING" -> DeliveryStatus.DELIVERY_STATUS_SENDING;
            case "MAIL_DELIVERY_STATUS_SENT", "SMS_DELIVERY_STATUS_SENT" -> DeliveryStatus.DELIVERY_STATUS_SENT;
            case "MAIL_DELIVERY_STATUS_RETRY", "SMS_DELIVERY_STATUS_RETRY" -> DeliveryStatus.DELIVERY_STATUS_RETRY;
            case "MAIL_DELIVERY_STATUS_FAILED", "SMS_DELIVERY_STATUS_FAILED" -> DeliveryStatus.DELIVERY_STATUS_FAILED;
            case "MAIL_DELIVERY_STATUS_SKIPPED", "SMS_DELIVERY_STATUS_SKIPPED" -> DeliveryStatus.DELIVERY_STATUS_SKIPPED;
            case "MAIL_DELIVERY_STATUS_CANCELED", "SMS_DELIVERY_STATUS_CANCELED" -> DeliveryStatus.DELIVERY_STATUS_CANCELED;
            default -> DeliveryStatus.DELIVERY_STATUS_UNSPECIFIED;
        };
    }

    private static Channel toChannel(String dbValue) {
        return switch (dbValue) {
            case "CHANNEL_EMAIL" -> Channel.CHANNEL_EMAIL;
            case "CHANNEL_SMS" -> Channel.CHANNEL_SMS;
            case "CHANNEL_PUSH" -> Channel.CHANNEL_PUSH;
            case "CHANNEL_TELEGRAM" -> Channel.CHANNEL_TELEGRAM;
            default -> Channel.CHANNEL_UNSPECIFIED;
        };
    }

    private static com.google.protobuf.Timestamp toTs(Instant instant) {
        return com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private record ChannelSummaryRow(
            String recipientId,
            Channel channel,
            int successfulCount,
            int unsuccessfulCount
    ) {
    }
}
