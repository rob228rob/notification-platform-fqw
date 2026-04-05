package ru.batoyan.vkr.notification.history.writer.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class DeliveryHistoryRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public boolean save(DeliveryStatusEvent event) {
        var payload = event.payload();
        var sql = """
                insert into nf_hist.delivery_history(
                    outbox_id, aggregate_type, aggregate_id, event_type, created_at,
                    dispatch_id, event_id, client_id, recipient_id, email, channel, delivery_status,
                    template_id, template_version, idempotency_key, attempt_no,
                    error_message, next_attempt_at, occurred_at, payload_json, headers_json
                ) values (
                    :outbox_id, :aggregate_type, :aggregate_id, :event_type, :created_at,
                    :dispatch_id, :event_id, :client_id, :recipient_id, :email, :channel, :delivery_status,
                    :template_id, :template_version, :idempotency_key, :attempt_no,
                    :error_message, :next_attempt_at, :occurred_at, cast(:payload_json as jsonb), cast(:headers_json as jsonb)
                )
                on conflict (outbox_id) do nothing
                """;

        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("outbox_id", event.outboxId())
                .addValue("aggregate_type", event.aggregateType())
                .addValue("aggregate_id", event.aggregateId())
                .addValue("event_type", event.eventType())
                .addValue("created_at", event.createdAt())
                .addValue("dispatch_id", asString(payload.get("dispatch_id")))
                .addValue("event_id", asString(payload.get("event_id")))
                .addValue("client_id", asString(payload.get("client_id")))
                .addValue("recipient_id", asString(payload.get("recipient_id")))
                .addValue("email", asString(payload.get("email")))
                .addValue("channel", asString(payload.get("channel")))
                .addValue("delivery_status", asString(payload.get("status")))
                .addValue("template_id", asString(payload.get("template_id")))
                .addValue("template_version", asInt(payload.get("template_version")))
                .addValue("idempotency_key", asString(payload.get("idempotency_key")))
                .addValue("attempt_no", asInt(payload.get("attempt_no")))
                .addValue("error_message", asNullableString(payload.get("error_message")))
                .addValue("next_attempt_at", parseOffsetDateTime(asNullableString(payload.get("next_attempt_at"))))
                .addValue("occurred_at", parseOffsetDateTime(asNullableString(payload.get("occurred_at"))))
                .addValue("payload_json", writeJson(payload))
                .addValue("headers_json", writeJson(event.headers()))) > 0;
    }

    public long countByClientId(String clientId) {
        var total = jdbc.queryForObject("""
                select count(*)
                from nf_hist.delivery_history
                where client_id = :client_id
                """, Map.of("client_id", clientId), Long.class);
        return total == null ? 0 : total;
    }

    public List<DeliveryStatusKafkaEvent> listByClientId(String clientId, int page, int size) {
        return jdbc.query("""
                select *
                from nf_hist.delivery_history
                where client_id = :client_id
                order by occurred_at desc nulls last, outbox_id desc
                limit :limit offset :offset
                """, new MapSqlParameterSource()
                .addValue("client_id", clientId)
                .addValue("limit", size)
                .addValue("offset", page * size), (rs, rowNum) -> toKafkaEvent(rs));
    }

    public RecipientDeliverySummary getRecipientSummary(String recipientId, int lookbackHours) {
        return getRecipientSummaries(List.of(recipientId), lookbackHours).getOrDefault(
                recipientId,
                emptySummary(recipientId, lookbackHours)
        );
    }

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

        var windowTo = OffsetDateTime.now();
        var windowFrom = windowTo.minusHours(Math.max(lookbackHours, 1));

        var rows = jdbc.query("""
                select recipient_id,
                       channel,
                       count(*) filter (
                           where delivery_status in ('MAIL_DELIVERY_STATUS_SENT', 'SMS_DELIVERY_STATUS_SENT')
                       ) as successful_count,
                       count(*) filter (
                           where delivery_status in (
                               'MAIL_DELIVERY_STATUS_FAILED', 'MAIL_DELIVERY_STATUS_SKIPPED',
                               'SMS_DELIVERY_STATUS_FAILED', 'SMS_DELIVERY_STATUS_SKIPPED'
                           )
                       ) as unsuccessful_count
                from nf_hist.delivery_history
                where recipient_id in (:recipient_ids)
                  and coalesce(occurred_at, created_at) >= :window_from
                  and coalesce(occurred_at, created_at) <= :window_to
                group by recipient_id, channel
                order by recipient_id, channel
                """, new MapSqlParameterSource()
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

    private DeliveryStatusKafkaEvent toKafkaEvent(ResultSet rs) throws SQLException {
        var payloadBuilder = DeliveryHistoryPayload.newBuilder()
                .setDispatchId(defaultString(rs.getString("dispatch_id")))
                .setEventId(defaultString(rs.getString("event_id")))
                .setClientId(defaultString(rs.getString("client_id")))
                .setRecipientId(defaultString(rs.getString("recipient_id")))
                .setEmail(defaultString(rs.getString("email")))
                .setChannel(toChannel(rs.getString("channel")))
                .setStatus(toStatus(rs.getString("delivery_status")))
                .setTemplateId(defaultString(rs.getString("template_id")))
                .setTemplateVersion(rs.getInt("template_version"))
                .setIdempotencyKey(defaultString(rs.getString("idempotency_key")))
                .setAttemptNo(rs.getInt("attempt_no"))
                .setErrorMessage(defaultString(rs.getString("error_message")));

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

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String asNullableString(Object value) {
        if (value == null) {
            return null;
        }
        var text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static int asInt(Object value) {
        if (value == null) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static OffsetDateTime parseOffsetDateTime(String value) {
        if (value == null) {
            return null;
        }
        return OffsetDateTime.parse(value);
    }

    private String writeJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map == null ? Collections.emptyMap() : map);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize json map", ex);
        }
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

    private static DeliveryStatus toStatus(String dbValue) {
        return switch (dbValue) {
            case "MAIL_DELIVERY_STATUS_PENDING", "SMS_DELIVERY_STATUS_PENDING" -> DeliveryStatus.DELIVERY_STATUS_PENDING;
            case "MAIL_DELIVERY_STATUS_SENDING", "SMS_DELIVERY_STATUS_SENDING" -> DeliveryStatus.DELIVERY_STATUS_SENDING;
            case "MAIL_DELIVERY_STATUS_SENT", "SMS_DELIVERY_STATUS_SENT" -> DeliveryStatus.DELIVERY_STATUS_SENT;
            case "MAIL_DELIVERY_STATUS_RETRY", "SMS_DELIVERY_STATUS_RETRY" -> DeliveryStatus.DELIVERY_STATUS_RETRY;
            case "MAIL_DELIVERY_STATUS_FAILED", "SMS_DELIVERY_STATUS_FAILED" -> DeliveryStatus.DELIVERY_STATUS_FAILED;
            case "MAIL_DELIVERY_STATUS_SKIPPED", "SMS_DELIVERY_STATUS_SKIPPED" -> DeliveryStatus.DELIVERY_STATUS_SKIPPED;
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

    private RecipientDeliverySummary emptySummary(String recipientId, int lookbackHours) {
        var windowTo = OffsetDateTime.now();
        var windowFrom = windowTo.minusHours(Math.max(lookbackHours, 1));
        return RecipientDeliverySummary.newBuilder()
                .setRecipientId(recipientId)
                .setWindowFrom(toTs(windowFrom.toInstant()))
                .setWindowTo(toTs(windowTo.toInstant()))
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
