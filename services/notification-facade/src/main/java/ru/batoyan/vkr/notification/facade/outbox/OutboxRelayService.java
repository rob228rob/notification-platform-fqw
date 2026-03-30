package ru.batoyan.vkr.notification.facade.outbox;

import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author batoyan.rl
 * @since 03.03.2026
 */
@Service
@RequiredArgsConstructor
public class OutboxRelayService {

    private static final Logger LOG = LogManager.getLogger();

    private final NamedParameterJdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final RetryTemplate retryTemplate;
    private final OutboxRelayProperties props;
    private final ObjectMapper objectMapper;


    @Scheduled(
            fixedDelayString = "${outbox.relay.fixed-delay}",
            scheduler = "outboxRelayTaskScheduler"
    )
    public void tick() {
        if (!props.isEnabled()) {
            LOG.warn("Outbox relay is disabled by properties");
            return;
        }
        LOG.info("Outbox relay enabled");
        try {
            var published = pollAndPublishOnce();
            if (published > 0) {
                LOG.info("[OUTBOX] published={}", published);
            }
        } catch (Exception e) {
            LOG.warn("[OUTBOX] tick failed: {}", e.getMessage(), e);
        }
    }

    public int pollAndPublishOnce() {
        var claimToken = UUID.randomUUID();
        var rows = claimBatch(claimToken);
        if (rows.isEmpty()) {
            LOG.info("[OUTBOX] poll: no unpublished rows");
            return 0;
        }

        LOG.info("[OUTBOX] poll: claimed {} row(s) (batchSize={}, claimToken={})",
                rows.size(), props.getBatchSize(), claimToken);

        var publishedIds = new ArrayList<Long>(rows.size());
        var failedIds = new ArrayList<Long>();

        for (var row : rows) {
            var topic = topicFor(row);
            var key = row.aggregateId();

            LOG.info("[OUTBOX] publish: outboxId={}, aggregateType={}, eventType={}, key={}, topic={}, payload: {}",
                    row.outboxId(), row.aggregateType(), row.eventType(), key, topic, row.payloadJson());

            try {
                retryTemplate.execute(ctx -> {
                    var attempt = ctx.getRetryCount() + 1;
                    LOG.info("[OUTBOX] publish attempt {}: outboxId={}, topic={}", attempt, row.outboxId(), topic);

                    try {
                        kafka.send(topic, key, serializePublisherEvent(row))
                                .whenComplete((r, e) -> {
                                    LOG.info("[OUTBOX] publish ok: outboxId={}, topic={}", row.outboxId(), topic);
                                })
                                .join();

                        return null;
                    } catch (Exception e) {
                        LOG.warn("[OUTBOX] publish failed (attempt {}): outboxId={}, topic={}, err={}",
                                attempt, row.outboxId(), topic, e.getMessage(), e);
                        throw e;
                    }
                });
                publishedIds.add(row.outboxId());
            } catch (Exception ex) {
                failedIds.add(row.outboxId());
            }
        }

        if (!publishedIds.isEmpty()) {
            LOG.info("[OUTBOX] marking published: ids={}, claimToken={}", publishedIds, claimToken);
            markPublished(publishedIds, claimToken);
        }

        if (!failedIds.isEmpty()) {
            LOG.info("[OUTBOX] releasing failed claims: ids={}, claimToken={}", failedIds, claimToken);
            releaseClaims(failedIds, claimToken);
        }

        LOG.info("[OUTBOX] poll done: published={}, failed={}", publishedIds.size(), failedIds.size());
        return publishedIds.size();
    }

    @Transactional
    protected List<OutboxRow> claimBatch(UUID claimToken) {
        var sql = """
                with candidate as (
                    select outbox_id
                    from %s.%s
                    where published_at is null
                      and (claim_token is null or claimed_at < :claimed_before)
                    order by outbox_id
                    for update skip locked
                    limit :limit
                )
                update %s.%s target
                set claim_token = :claim_token,
                    claimed_at = now()
                from candidate
                where target.outbox_id = candidate.outbox_id
                returning target.outbox_id,
                          target.aggregate_type,
                          target.aggregate_id,
                          target.event_type,
                          target.payload::text as payload_json,
                          target.headers::text as headers_json,
                          target.created_at
                """.formatted(props.getSchema(), props.getTable(), props.getSchema(), props.getTable());

        return jdbc.query(sql, new MapSqlParameterSource()
                .addValue("limit", props.getBatchSize())
                .addValue("claim_token", claimToken)
                .addValue("claimed_before", OffsetDateTime.now().minus(props.getLeaseDuration())), (rs, n) -> new OutboxRow(
                rs.getLong("outbox_id"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                rs.getString("payload_json"),
                rs.getString("headers_json"),
                rs.getTimestamp("created_at").toInstant()
        ));
    }

    @Transactional
    protected void markPublished(List<Long> ids, UUID claimToken) {
        var sql = """
        update %s.%s
        set published_at = now(),
            claim_token = null,
            claimed_at = null
        where outbox_id = any(:ids)
          and claim_token = :claim_token
        """.formatted(props.getSchema(), props.getTable());

        var p = new MapSqlParameterSource()
                .addValue("ids", ids.toArray(Long[]::new))
                .addValue("claim_token", claimToken);
        jdbc.update(sql, p);
    }

    @Transactional
    protected void releaseClaims(List<Long> ids, UUID claimToken) {
        var sql = """
        update %s.%s
        set claim_token = null,
            claimed_at = null
        where outbox_id = any(:ids)
          and claim_token = :claim_token
          and published_at is null
        """.formatted(props.getSchema(), props.getTable());

        var p = new MapSqlParameterSource()
                .addValue("ids", ids.toArray(Long[]::new))
                .addValue("claim_token", claimToken);
        jdbc.update(sql, p);
    }

    private String topicFor(OutboxRow row) {
        var topics = props.getTopics();
        return switch (row.aggregateType()) {
            case "notification_event" -> topics.events();
            case "dispatch" -> topics.dispatches();
            case "mail_dispatch" -> isDelayedMailDispatch(row) ? topics.mailDispatchesScheduled() : topics.mailDispatches();
            case "sms_dispatch" -> topics.smsDispatches();
            default -> topics.events();
        };
    }

    private boolean isDelayedMailDispatch(OutboxRow row) {
        var payload = readJsonMap(row.payloadJson());
        var rawPlannedSendAt = firstPresent(payload, "planned_send_at", "plannedSendAt", "send_at", "sendAt");
        if (rawPlannedSendAt == null) {
            return false;
        }

        var plannedSendAtText = String.valueOf(rawPlannedSendAt).trim();
        if (plannedSendAtText.isEmpty() || "null".equalsIgnoreCase(plannedSendAtText)) {
            return false;
        }

        var plannedSendAt = parseInstant(plannedSendAtText, row.outboxId());
        return plannedSendAt != null && plannedSendAt.isAfter(Instant.now());
    }

    private Object firstPresent(Map<String, Object> payload, String... keys) {
        for (var key : keys) {
            var value = payload.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Instant parseInstant(String value, long outboxId) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException ex) {
                LOG.warn("[OUTBOX] Failed to parse planned_send_at for outboxId={}, value={}", outboxId, value);
                return null;
            }
        }
    }

    private String serializePublisherEvent(OutboxRow row) {
        try {
            var event = new PublisherEvent(
                    row.outboxId(),
                    row.aggregateType(),
                    row.aggregateId(),
                    row.eventType(),
                    readJsonMap(row.payloadJson()),
                    readJsonMap(row.headersJson()),
                    row.createdAt()
            );
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox message " + row.outboxId(), e);
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        try {
            if (json == null || json.isBlank()) {
                return Collections.emptyMap();
            }
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read outbox payload", e);
        }
    }

    @NullMarked
    public record PublisherEvent(
            long outboxId,
            String aggregateType,
            String aggregateId,
            String eventType,
            Map<String, Object> payload,
            Map<String, Object> headers,
            Instant createdAt
    ) {}

    @NullMarked
    public record OutboxRow(
            long outboxId,
            String aggregateType,
            String aggregateId,
            String eventType,
            String payloadJson,
            String headersJson,
            Instant createdAt
    ) {}
}
