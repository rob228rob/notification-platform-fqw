package ru.batoyan.vkr.notification.mail.sender.services.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailInboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public boolean storeIncomingDispatchEvent(Map<String, Object> message) {
        return storeIncomingEvent(message, AggregateType.MAIL_DISPATCH, EventType.MAIL_DISPATCH_REQUESTED, "dispatch_id");
    }

    public boolean storeIncomingNotificationEvent(Map<String, Object> message) {
        return storeIncomingEvent(message, AggregateType.NOTIFICATION_EVENT, EventType.EVENT_CREATED, "event_id");
    }

    private boolean storeIncomingEvent(Map<String, Object> message,
                                       AggregateType expectedAggregateType,
                                       EventType expectedEventType,
                                       String messageIdField) {
        var aggregateType = asString(message.get("aggregateType"));
        var aggregateId = asString(message.get("aggregateId"));
        var eventType = asString(message.get("eventType"));

        if (!expectedAggregateType.matches(aggregateType) || !expectedEventType.matches(eventType)) {
            log.warn("Unexpected kafka event skipped: expectedAggregateType={}, expectedEventType={}, aggregateType={}, aggregateId={}, eventType={}",
                    expectedAggregateType.dbValue(), expectedEventType.dbValue(), aggregateType, aggregateId, eventType);
            return false;
        }

        var payload = asMap(message.get("payload"));
        var messageId = asString(payload.getOrDefault(messageIdField, aggregateId));
        var eventId = asString(payload.getOrDefault("event_id", aggregateId));

        try {
            jdbc.update("""
                    insert into nf.consumer_inbox_message(
                      message_id, aggregate_type, aggregate_id, event_type,
                      event_id,
                      payload, headers, processing_status, received_at
                    ) values (
                      :message_id, :aggregate_type, :aggregate_id, :event_type,
                      :event_id,
                      cast(:payload as jsonb), cast(:headers as jsonb), :processing_status, :received_at
                    )
                    """, new MapSqlParameterSource()
                    .addValue("message_id", java.util.UUID.fromString(messageId))
                    .addValue("aggregate_type", aggregateType)
                    .addValue("aggregate_id", aggregateId)
                    .addValue("event_type", eventType)
                    .addValue("event_id", java.util.UUID.fromString(eventId))
                    .addValue("payload", Jsons.write(payload))
                    .addValue("headers", Jsons.write(asMap(message.get("headers"))))
                    .addValue("processing_status", "NEW")
                    .addValue("received_at", OffsetDateTime.now()));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    static String asString(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }
}
