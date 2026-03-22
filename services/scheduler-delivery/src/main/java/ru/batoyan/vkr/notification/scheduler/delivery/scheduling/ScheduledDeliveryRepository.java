package ru.batoyan.vkr.notification.scheduler.delivery.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ScheduledDeliveryRepository {

    private static final Logger LOG = LogManager.getLogger();

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public boolean save(KafkaEnvelope envelope) {
        var payload = envelope.payload();
        var plannedSendAt = resolvePlannedSendAt(payload);
        var messageId = resolveMessageId(envelope);
        return jdbc.update("""
                insert into nf_sched.scheduled_delivery_task(
                    message_id, aggregate_type, aggregate_id, event_type,
                    payload, headers, planned_send_at, source_created_at,
                    status, attempt_count, created_at
                ) values (
                    :message_id, :aggregate_type, :aggregate_id, :event_type,
                    cast(:payload as jsonb), cast(:headers as jsonb), :planned_send_at, :source_created_at,
                    :status, 0, now()
                )
                on conflict (message_id) do nothing
                """, new MapSqlParameterSource()
                .addValue("message_id", messageId)
                .addValue("aggregate_type", envelope.aggregateType())
                .addValue("aggregate_id", envelope.aggregateId())
                .addValue("event_type", envelope.eventType())
                .addValue("payload", toJson(envelope.payload()))
                .addValue("headers", toJson(envelope.headers()))
                .addValue("planned_send_at", plannedSendAt)
                .addValue("source_created_at", envelope.createdAt())
                .addValue("status", "NEW")) > 0;
    }

    public List<ScheduledTask> lockDueTasks(int batchSize) {
        return jdbc.query("""
                with candidate as (
                    select task_id
                    from nf_sched.scheduled_delivery_task
                    where status in ('NEW', 'RETRY')
                      and planned_send_at <= :now_ts
                      and (next_retry_at is null or next_retry_at <= :now_ts)
                    order by planned_send_at, task_id
                    for update skip locked
                    limit :limit
                )
                update nf_sched.scheduled_delivery_task t
                set status = 'PUBLISHING'
                from candidate
                where t.task_id = candidate.task_id
                returning t.task_id, t.aggregate_type, t.aggregate_id, t.event_type,
                          t.payload::text as payload_json, t.headers::text as headers_json,
                          t.attempt_count, t.source_created_at
                """, new MapSqlParameterSource()
                .addValue("now_ts", OffsetDateTime.now())
                .addValue("limit", batchSize), (rs, rowNum) -> mapTask(rs));
    }

    public void markPublished(long taskId) {
        jdbc.update("""
                update nf_sched.scheduled_delivery_task
                set status = 'PUBLISHED',
                    published_at = now(),
                    last_error = null
                where task_id = :task_id
                """, Map.of("task_id", taskId));
    }

    public void markRetry(long taskId, String errorMessage, OffsetDateTime retryAt) {
        jdbc.update("""
                update nf_sched.scheduled_delivery_task
                set status = 'RETRY',
                    attempt_count = attempt_count + 1,
                    next_retry_at = :next_retry_at,
                    last_error = :last_error
                where task_id = :task_id
                """, new MapSqlParameterSource()
                .addValue("task_id", taskId)
                .addValue("next_retry_at", retryAt)
                .addValue("last_error", truncate(errorMessage)));
    }

    private ScheduledTask mapTask(ResultSet rs) throws SQLException {
        return new ScheduledTask(
                rs.getLong("task_id"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                fromJson(rs.getString("payload_json")),
                fromJson(rs.getString("headers_json")),
                rs.getInt("attempt_count"),
                rs.getObject("source_created_at", OffsetDateTime.class)
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize task json", ex);
        }
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize task json", ex);
        }
    }

    private static UUID resolveMessageId(KafkaEnvelope envelope) {
        var headerMessageId = envelope.headers().get("message_id");
        if (headerMessageId != null) {
            return safeUuid(String.valueOf(headerMessageId));
        }
        if (!envelope.aggregateId().isBlank()) {
            return safeUuid(envelope.aggregateId());
        }
        var dispatchId = envelope.payload().get("dispatch_id");
        if (dispatchId != null) {
            return safeUuid(String.valueOf(dispatchId));
        }
        return UUID.nameUUIDFromBytes((envelope.eventType() + "|" + envelope.payload()).getBytes(StandardCharsets.UTF_8));
    }

    private static OffsetDateTime resolvePlannedSendAt(Map<String, Object> payload) {
        for (var key : List.of("planned_send_at", "plannedSendAt", "send_at", "sendAt")) {
            var value = payload.get(key);
            if (value == null) {
                continue;
            }
            var text = String.valueOf(value);
            if (text.isBlank() || "null".equalsIgnoreCase(text)) {
                continue;
            }
            return OffsetDateTime.parse(text);
        }
        return OffsetDateTime.now();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 512 ? value.substring(0, 512) : value;
    }

    private static UUID safeUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception ignored) {
            LOG.warn("Message id is not UUID, fallback to name-based UUID: {}", value);
            return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    public record ScheduledTask(
            long taskId,
            String aggregateType,
            String aggregateId,
            String eventType,
            Map<String, Object> payload,
            Map<String, Object> headers,
            int attemptCount,
            OffsetDateTime sourceCreatedAt
    ) {
    }
}
