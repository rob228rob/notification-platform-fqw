package ru.batoyan.vkr.sms.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.batoyan.vkr.sms.kafka.model.AggregateType;
import ru.batoyan.vkr.sms.kafka.model.EventType;
import ru.batoyan.vkr.sms.kafka.model.NotificationEventProcessingProperties;
import ru.batoyan.vkr.sms.kafka.policy.SmsDeliveryPlanService;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationEventWorkflow {

    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationEventProcessingProperties properties;
    private final SmsDeliveryPlanService smsDeliveryPlanService;

    @Transactional
    @Scheduled(fixedDelayString = "${delivery.notification-event.fixed-delay}")
    public void processInboxTick() {
        if (!properties.enabled()) {
            return;
        }
        var processed = processInboxBatchNotificationEvents();
        if (processed > 0) {
            log.info("Notification event worker processed {} row(s)", processed);
        }
    }

    @Transactional
    public int processInboxBatchNotificationEvents() {
        var rows = lockInboxBatch();
        for (var row : rows) {
            try {
                handleInboxRow(row);
                updateInboxStatus(row.messageId(), InboxStatus.PROCESSED, null);
            } catch (Exception ex) {
                log.warn("Notification event row failed messageId={}, eventId={}, err={}", row.messageId(), row.eventId(), ex.getMessage(), ex);
                updateInboxStatus(row.messageId(), InboxStatus.FAILED, ex.getMessage());
            }
        }
        return rows.size();
    }

    private void handleInboxRow(InboxRow row) {
        var event = EventCreatedMessage.fromPayload(Jsons.read(row.payloadJson()));
        if (!SmsDeliveryPlanService.CHANNEL_SMS.equals(event.preferredChannel())) {
            return;
        }
        if (event.recipientIds().isEmpty()) {
            log.warn("SMS notification event skipped because recipient_ids are empty eventId={}, clientId={}",
                    event.eventId(), event.clientId());
            return;
        }

        var decisions = smsDeliveryPlanService.evaluateRecipients(event.recipientIds());
        for (var recipientId : event.recipientIds()) {
            log.info("Planning SMS notification event delivery eventId={}, recipientId={}", event.eventId(), recipientId);
            var decision = decisions.get(recipientId);
            if (decision == null || !decision.allowed()) {
                log.info("SMS notification event delivery skipped eventId={}, recipientId={}, reasonCode={}, destination={}",
                        event.eventId(), recipientId,
                        decision == null ? "PROFILE_NOT_FOUND" : decision.reasonCode(),
                        decision == null ? "" : decision.destination());
                smsDeliveryPlanService.saveSkippedDelivery(event.eventId(), event.eventId(), event.clientId(), recipientId,
                        decision == null ? "PROFILE_NOT_FOUND" : decision.reasonCode(),
                        decision == null ? "" : decision.destination(), event.templateId(), event.templateVersion(), event.payload());
                continue;
            }
            log.info("SMS notification event delivery planned eventId={}, recipientId={}, phone={}",
                    event.eventId(), recipientId, decision.destination());
            smsDeliveryPlanService.createPendingDelivery(event.eventId(), event.eventId(), event.clientId(), recipientId,
                    decision.destination(), event.templateId(), event.templateVersion(), event.payload());
        }
    }

    private List<InboxRow> lockInboxBatch() {
        return jdbc.query("""
                select message_id, event_id, aggregate_type, event_type, payload::text as payload_json, processing_status
                from nf_sms.consumer_inbox_message
                where processing_status = :status
                  and aggregate_type = :aggregate_type
                  and event_type = :event_type
                order by received_at, message_id
                for update skip locked
                limit :limit
                """, new MapSqlParameterSource()
                .addValue("status", InboxStatus.NEW.dbValue())
                .addValue("aggregate_type", AggregateType.NOTIFICATION_EVENT.dbValue())
                .addValue("event_type", EventType.EVENT_CREATED.dbValue())
                .addValue("limit", properties.inboxBatchSize()), (rs, rowNum) -> new InboxRow(
                UUID.fromString(rs.getString("message_id")),
                UUID.fromString(rs.getString("event_id")),
                AggregateType.fromDb(rs.getString("aggregate_type")),
                EventType.fromDb(rs.getString("event_type")),
                rs.getString("payload_json"),
                InboxStatus.fromDb(rs.getString("processing_status"))
        ));
    }

    private void updateInboxStatus(UUID messageId, InboxStatus status, String errorMessage) {
        jdbc.update("""
                update nf_sms.consumer_inbox_message
                set processing_status = :status,
                    processed_at = :processed_at,
                    error_message = :error_message
                where message_id = :message_id
                """, new MapSqlParameterSource()
                .addValue("status", status.dbValue())
                .addValue("processed_at", status == InboxStatus.PROCESSED ? OffsetDateTime.now() : null)
                .addValue("error_message", truncate(errorMessage))
                .addValue("message_id", messageId));
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }

    private enum InboxStatus implements DbMappedEnum {
        NEW("NEW"), PROCESSED("PROCESSED"), FAILED("FAILED");
        private final String dbValue;
        InboxStatus(String dbValue) { this.dbValue = dbValue; }
        @Override public String dbValue() { return dbValue; }
        static InboxStatus fromDb(String value) { return DbMappedEnum.fromDb(values(), value, InboxStatus.class.getSimpleName()); }
    }

    private interface DbMappedEnum {
        String dbValue();
        static <E extends Enum<E> & DbMappedEnum> E fromDb(E[] values, String dbValue, String enumName) {
            return Arrays.stream(values).filter(value -> value.dbValue().equals(dbValue)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown " + enumName + " db value: " + dbValue));
        }
    }

    private record InboxRow(UUID messageId, UUID eventId, AggregateType aggregateType, EventType eventType, String payloadJson, InboxStatus status) {}

    private record EventCreatedMessage(UUID eventId, String clientId, String templateId, int templateVersion, String preferredChannel, Map<String, Object> payload, List<String> recipientIds) {
        private static EventCreatedMessage fromPayload(Map<String, Object> payload) {
            requireField(payload, "event_id");
            requireField(payload, "client_id");
            requireField(payload, "template_id");
            requireField(payload, "template_version");
            requireField(payload, "preferred_channel");
            return new EventCreatedMessage(
                    UUID.fromString(String.valueOf(payload.get("event_id"))),
                    String.valueOf(payload.get("client_id")),
                    String.valueOf(payload.get("template_id")),
                    Integer.parseInt(String.valueOf(payload.get("template_version"))),
                    String.valueOf(payload.get("preferred_channel")),
                    SmsInboxRepository.asMap(payload.get("payload")),
                    asStringList(payload.get("recipient_ids")));
        }

        private static void requireField(Map<String, Object> payload, String fieldName) {
            if (!payload.containsKey(fieldName) || payload.get(fieldName) == null) {
                throw new IllegalArgumentException("Inbox payload missing required field: " + fieldName);
            }
        }
    }
}
