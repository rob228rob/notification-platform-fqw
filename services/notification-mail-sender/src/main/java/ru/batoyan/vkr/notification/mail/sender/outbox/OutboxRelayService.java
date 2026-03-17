package ru.batoyan.vkr.notification.mail.sender.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelayService {

    private final NamedParameterJdbcTemplate jdbc;
    private final KafkaTemplate<String, String> outboxKafkaTemplate;
    private final RetryTemplate outboxRetryTemplate;
    private final OutboxRelayProperties props;
    private final ObjectMapper objectMapper;

    @Scheduled(
            fixedDelayString = "${outbox.relay.fixed-delay}",
            scheduler = "outboxRelayTaskScheduler"
    )
    public void tick() {
        if (!props.isEnabled()) {
            return;
        }
        try {
            var published = pollAndPublishOnce();
            if (published > 0) {
                log.info("Mail-sender outbox published {} message(s)", published);
            }
        } catch (Exception ex) {
            log.warn("Mail-sender outbox tick failed: {}", ex.getMessage(), ex);
        }
    }

    public int pollAndPublishOnce() {
        var claimToken = UUID.randomUUID();
        var rows = claimBatch(claimToken);
        if (rows.isEmpty()) {
            return 0;
        }

        var publishedIds = new ArrayList<Long>(rows.size());
        var failedIds = new ArrayList<Long>();

        for (var row : rows) {
            var topic = topicFor(row.aggregateType());
            try {
                outboxRetryTemplate.execute(ctx -> {
                    outboxKafkaTemplate.send(topic, row.aggregateId(), serializeEvent(row)).join();
                    return null;
                });
                publishedIds.add(row.outboxId());
            } catch (Exception ex) {
                failedIds.add(row.outboxId());
                log.warn("Outbox publish failed outboxId={}, topic={}, err={}", row.outboxId(), topic, ex.getMessage(), ex);
            }
        }

        if (!publishedIds.isEmpty()) {
            markPublished(publishedIds, claimToken);
        }
        if (!failedIds.isEmpty()) {
            releaseClaims(failedIds, claimToken);
        }
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
                .addValue("claimed_before", OffsetDateTime.now().minus(props.getLeaseDuration())), (rs, rowNum) -> new OutboxRow(
                rs.getLong("outbox_id"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                rs.getString("payload_json"),
                rs.getString("headers_json"),
                rs.getObject("created_at", OffsetDateTime.class)
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
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("ids", ids.toArray(Long[]::new))
                .addValue("claim_token", claimToken));
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
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("ids", ids.toArray(Long[]::new))
                .addValue("claim_token", claimToken));
    }

    private String topicFor(String aggregateType) {
        if ("mail_delivery".equals(aggregateType)) {
            return props.getTopics().getMailDeliveryStatuses();
        }
        return props.getTopics().getMailDeliveryStatuses();
    }

    private String serializeEvent(OutboxRow row) {
        try {
            var event = new PublishedEvent(
                    row.outboxId(),
                    row.aggregateType(),
                    row.aggregateId(),
                    row.eventType(),
                    readJsonMap(row.payloadJson()),
                    readJsonMap(row.headersJson()),
                    row.createdAt()
            );
            return objectMapper.writeValueAsString(event);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize outbox event " + row.outboxId(), ex);
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        try {
            if (json == null || json.isBlank()) {
                return Collections.emptyMap();
            }
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse outbox json", ex);
        }
    }

    public record OutboxRow(
            long outboxId,
            String aggregateType,
            String aggregateId,
            String eventType,
            String payloadJson,
            String headersJson,
            OffsetDateTime createdAt
    ) {
    }

    public record PublishedEvent(
            long outboxId,
            String aggregateType,
            String aggregateId,
            String eventType,
            Map<String, Object> payload,
            Map<String, Object> headers,
            OffsetDateTime createdAt
    ) {
    }
}
