package ru.batoyan.vkr.notification.mail.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.batoyan.vkr.notification.mail.kafka.model.AggregateType;
import ru.batoyan.vkr.notification.mail.kafka.model.EventType;
import ru.batoyan.vkr.notification.mail.kafka.model.NotificationEventProcessingProperties;
import ru.batoyan.vkr.notification.mail.kafka.policy.MailDeliveryPlanService;

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
    private final MailDeliveryPlanService mailDeliveryPlanService;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedRateString = "${delivery.notification-event.fixed-delay}")
    public void processInboxTick() {
        if (!properties.isEnabled()) {
            log.debug("Notification event worker disabled");
            return;
        }
        var processed = processInboxBatch();
        if (processed > 0) {
            log.info("Notification event worker processed {} row(s)", processed);
        } else {
            log.debug("Notification event worker found no rows");
        }
    }

    public int processInboxBatch() {
        var rows = transactionTemplate.execute(status -> claimInboxBatchTx());
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        log.debug("Notification event worker locked {} row(s)", rows.size());
        for (var row : rows) {
            try {
                handleInboxRow(row);
                updateInboxStatus(row.messageId(), InboxStatus.PROCESSED, null);
                log.info("Notification event processed messageId={}, eventId={}", row.messageId(), row.eventId());
            } catch (Exception ex) {
                log.warn("Notification event row failed messageId={}, eventId={}, err={}",
                        row.messageId(), row.eventId(), ex.getMessage(), ex);
                updateInboxStatus(row.messageId(), InboxStatus.FAILED, ex.getMessage());
            }
        }
        return rows.size();
    }

    private List<InboxRow> claimInboxBatchTx() {
        var rows = lockInboxBatch();
        for (var row : rows) {
            updateInboxStatus(row.messageId(), InboxStatus.PROCESSING, null);
        }
        return rows;
    }

    private void handleInboxRow(InboxRow row) {
        var payload = Jsons.read(row.payloadJson());
        var event = EventCreatedMessage.fromPayload(payload);
        log.info("Notification event details eventId={}, templateId={}, templateVersion={}, preferredChannel={}, priority={}",
                event.eventId(), event.templateId(), event.templateVersion(), event.preferredChannel(), event.priority());
        log.debug("Notification event payload eventId={}, payload={}", event.eventId(), event.payload());

        if (!MailDeliveryPlanService.CHANNEL_EMAIL.equals(event.preferredChannel())) {
            log.info("Notification event skipped because preferredChannel is not email, eventId={}, preferredChannel={}",
                    event.eventId(), event.preferredChannel());
            return;
        }

        if (event.recipientIds().isEmpty()) {
            log.warn("Notification event skipped because recipient_ids are empty eventId={}, clientId={}",
                    event.eventId(), event.clientId());
            return;
        }

        for (var recipientId : event.recipientIds()) {
            log.info("Planning notification event delivery eventId={}, recipientId={}", event.eventId(), recipientId);
            var decision = mailDeliveryPlanService.evaluateRecipient(recipientId);
            if (!decision.allowed()) {
                log.warn("Notification event delivery skipped by business rule eventId={}, recipientId={}, reasonCode={}",
                        event.eventId(), recipientId, decision.reasonCode());
                mailDeliveryPlanService.saveSkippedDelivery(
                        event.eventId(),
                        event.eventId(),
                        event.clientId(),
                        recipientId,
                        decision.reasonCode(),
                        decision.email(),
                        event.templateId(),
                        event.templateVersion(),
                        event.payload()
                );
                continue;
            }

            mailDeliveryPlanService.createPendingDelivery(
                    event.eventId(),
                    event.eventId(),
                    event.clientId(),
                    recipientId,
                    decision.email(),
                    event.templateId(),
                    event.templateVersion(),
                    event.payload()
            );
            log.info("Notification event delivery planned eventId={}, recipientId={}, email={}",
                    event.eventId(), recipientId, decision.email());
        }
    }

    private List<InboxRow> lockInboxBatch() {
        return jdbc.query("""
                select message_id, event_id, aggregate_type, event_type, payload::text as payload_json, processing_status
                from nf_mail.consumer_inbox_message
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
                .addValue("limit", properties.getInboxBatchSize()), (rs, rowNum) -> new InboxRow(
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
                update nf_mail.consumer_inbox_message
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
        if (value == null) {
            return null;
        }
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
        NEW("NEW"),
        PROCESSING("PROCESSING"),
        PROCESSED("PROCESSED"),
        FAILED("FAILED");

        private final String dbValue;

        InboxStatus(String dbValue) {
            this.dbValue = dbValue;
        }

        @Override
        public String dbValue() {
            return dbValue;
        }

        static InboxStatus fromDb(String value) {
            return DbMappedEnum.fromDb(values(), value, InboxStatus.class.getSimpleName());
        }
    }

    private interface DbMappedEnum {
        String dbValue();

        static <E extends Enum<E> & DbMappedEnum> E fromDb(E[] values, String dbValue, String enumName) {
            return Arrays.stream(values)
                    .filter(value -> value.dbValue().equals(dbValue))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown " + enumName + " db value: " + dbValue));
        }
    }

    private record InboxRow(
            UUID messageId,
            UUID eventId,
            AggregateType aggregateType,
            EventType eventType,
            String payloadJson,
            InboxStatus status
    ) {
    }

    private record EventCreatedMessage(
            UUID eventId,
            String clientId,
            String templateId,
            int templateVersion,
            String preferredChannel,
            String priority,
            Map<String, Object> payload,
            List<String> recipientIds
    ) {
        private static EventCreatedMessage fromPayload(Map<String, Object> payload) {
            requireField(payload, "event_id");
            requireField(payload, "client_id");
            requireField(payload, "template_id");
            requireField(payload, "template_version");
            requireField(payload, "preferred_channel");
            requireField(payload, "priority");
            return new EventCreatedMessage(
                    UUID.fromString(String.valueOf(payload.get("event_id"))),
                    String.valueOf(payload.get("client_id")),
                    String.valueOf(payload.get("template_id")),
                    Integer.parseInt(String.valueOf(payload.get("template_version"))),
                    String.valueOf(payload.get("preferred_channel")),
                    String.valueOf(payload.get("priority")),
                    MailInboxRepository.asMap(payload.get("payload")),
                    asStringList(payload.get("recipient_ids"))
            );
        }

        private static void requireField(Map<String, Object> payload, String fieldName) {
            if (!payload.containsKey(fieldName) || payload.get(fieldName) == null) {
                throw new IllegalArgumentException("Inbox payload missing required field: " + fieldName);
            }
        }
    }
}
