package ru.batoyan.vkr.outbox;

import lombok.RequiredArgsConstructor;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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


    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay}")
    @Transactional
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

    @Transactional
    public int pollAndPublishOnce() {
        var rows = lockBatch();
        if (rows.isEmpty()) {
            LOG.debug("[OUTBOX] poll: no unpublished rows");
            return 0;
        }

        LOG.info("[OUTBOX] poll: locked {} row(s) (batchSize={})", rows.size(), props.getBatchSize());

        var ids = new ArrayList<Long>(rows.size());

        for (var row : rows) {
            var topic = topicFor(row.aggregateType());
            var key = row.aggregateId();

            LOG.debug("[OUTBOX] publish: outboxId={}, aggregateType={}, eventType={}, key={}, topic={}",
                    row.outboxId(), row.aggregateType(), row.eventType(), key, topic);

            retryTemplate.execute(ctx -> {
                var attempt = ctx.getRetryCount() + 1;
                LOG.debug("[OUTBOX] publish attempt {}: outboxId={}, topic={}", attempt, row.outboxId(), topic);

                try {
                    kafka.send(topic, key, row.payloadJson()).join();
                    LOG.debug("[OUTBOX] publish ok: outboxId={}, topic={}", row.outboxId(), topic);
                    return null;
                } catch (Exception e) {
                    LOG.warn("[OUTBOX] publish failed (attempt {}): outboxId={}, topic={}, err={}",
                            attempt, row.outboxId(), topic, e.getMessage(), e);
                    throw e;
                }
            });

            ids.add(row.outboxId());
        }

        LOG.info("[OUTBOX] marking published: ids={}", ids);
        markPublished(ids);

        LOG.info("[OUTBOX] poll done: published={}", ids.size());
        return ids.size();
    }

    private List<OutboxRow> lockBatch() {
        var sql = """
                select outbox_id, aggregate_type, aggregate_id, event_type,
                       payload::text as payload_json, headers::text as headers_json
                from %s.%s
                where published_at is null
                order by outbox_id
                for update skip locked
                limit :limit""".formatted(props.getSchema(), props.getTable());

        return jdbc.query(sql, Map.of("limit", props.getBatchSize()), (rs, n) -> new OutboxRow(
                rs.getLong("outbox_id"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                rs.getString("payload_json"),
                rs.getString("headers_json")
        ));
    }

    private void markPublished(List<Long> ids) {
        var sql = """
        update %s.%s
        set published_at = now()
        where outbox_id = any(:ids)
        """.formatted(props.getSchema(), props.getTable());

        var p = new MapSqlParameterSource().addValue("ids", ids.toArray(Long[]::new));
        jdbc.update(sql, p);
    }

    private String topicFor(String aggregateType) {
        var topics = props.getTopics();
        return switch (aggregateType) {
            case "notification_event" -> topics.events();
            case "dispatch" -> topics.dispatches();
            default -> topics.events();
        };
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
            String headersJson
    ) {}
}
