package ru.batoyan.vkr.notification.mail.sender.services.kafka;

import jakarta.validation.constraints.Null;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.N;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailDispatchWorkflow {

    private static final String CHANNEL_EMAIL = "CHANNEL_EMAIL";
    private static final String AGGREGATE_TYPE_MAIL_DISPATCH = "mail_dispatch";
    private static final String EVENT_TYPE_MAIL_DISPATCH_REQUESTED = "MailDispatchRequested";

    private final NamedParameterJdbcTemplate jdbc;
    private final MailGateway mailGateway;
    private final MailDeliveryProperties properties;
    private final MailDeliveryPlanService mailDeliveryPlanService;

    @Scheduled(fixedDelayString = "${delivery.mail.inbox-fixed-delay}")
    public void processInboxTick() {
        if (!properties.isEnabled()) {
            return;
        }
        var processed = processInboxBatch();
        if (processed > 0) {
            log.info("Mail inbox worker processed {} record(s)", processed);
        }
    }

    @Transactional
    @Scheduled(fixedDelayString = "${delivery.mail.delivery-fixed-delay}")
    public void processDeliveryTick() {
        if (!properties.isEnabled()) {
            return;
        }
        var processed = processDeliveryBatch();
        if (processed > 0) {
            log.info("Mail delivery worker processed {} record(s)", processed);
        }
    }

    @Transactional
    public int processInboxBatch() {
        var rows = lockInboxBatch();
        for (var row : rows) {
            try {
                handleInboxRow(row);
                updateInboxStatus(row.messageId(), InboxStatus.PROCESSED, null);
            } catch (Exception ex) {

                log.warn("Mail inbox row failed messageId={}, err={}", row.messageId(), ex.getMessage(), ex);
                updateInboxStatus(row.messageId(), InboxStatus.FAILED, ex.getMessage());
            }
        }
        return rows.size();
    }

    @Transactional
    public int processDeliveryBatch() {
        var rows = lockDeliveryBatch();
        for (var row : rows) {
            processSingleDelivery(row);
        }
        return rows.size();
    }

    private void handleInboxRow(InboxRow row) {
        if (!AGGREGATE_TYPE_MAIL_DISPATCH.equals(row.aggregateType())
                || !EVENT_TYPE_MAIL_DISPATCH_REQUESTED.equals(row.eventType())) {
            log.warn("Unexpected inbox row skipped messageId={}, aggregateType={}, eventType={}",
                    row.messageId(), row.aggregateType(), row.eventType());
            return;
        }
        var payload = Jsons.read(row.payloadJson());
        var dispatch = DispatchMessage.fromPayload(payload);
        if (!MailDeliveryPlanService.CHANNEL_EMAIL.equals(dispatch.preferredChannel())) {
            log.debug("Skip non-email dispatch dispatchId={}, channel={}", dispatch.dispatchId(), dispatch.preferredChannel());
            return;
        }

        for (var recipientId : dispatch.recipientIds()) {
            var decision = mailDeliveryPlanService.evaluateRecipient(recipientId);
            if (!decision.allowed()) {
                mailDeliveryPlanService.saveSkippedDelivery(
                        dispatch.dispatchId(),
                        dispatch.eventId(),
                        recipientId,
                        decision.reasonCode(),
                        decision.email(),
                        dispatch.templateId(),
                        dispatch.templateVersion(),
                        dispatch.payload()
                );
                continue;
            }
            mailDeliveryPlanService.createPendingDelivery(
                    dispatch.dispatchId(),
                    dispatch.eventId(),
                    recipientId,
                    decision.email(),
                    dispatch.templateId(),
                    dispatch.templateVersion(),
                    dispatch.payload()
            );
        }
    }

    private void processSingleDelivery(MailDeliveryRow row) {
        var attemptNo = row.attemptCount() + 1;
        try {
            updateDeliveryStatus(row.dispatchId(), row.recipientId(), DeliveryStatus.SENDING, null, row.attemptCount(), row.nextAttemptAt());
            createAttempt(row.dispatchId(), row.recipientId(), row.channel(), attemptNo, AttemptStatus.STARTED, null);

            mailGateway.send(new MailGateway.MailMessage(
                    row.dispatchId().toString() + ":" + row.recipientId() + ":" + row.channel(),
                    row.idempotencyKey(),
                    row.recipientId(),
                    row.email(),
                    row.templateId(),
                    row.templateVersion(),
                    row.payloadJson()
            ));

            updateAttempt(row.dispatchId(), row.recipientId(), row.channel(), attemptNo, AttemptStatus.SUCCESS, null);
            jdbc.update("""
                    update nf.mail_delivery
                    set status = :status,
                        attempt_count = attempt_count + 1,
                        sent_at = :sent_at,
                        next_attempt_at = null,
                        last_error = null
                    where dispatch_id = :dispatch_id
                      and recipient_id = :recipient_id
                      and channel = :channel
                    """, Map.of(
                    "status", DeliveryStatus.SENT.dbValue(),
                    "sent_at", OffsetDateTime.now(),
                    "dispatch_id", row.dispatchId(),
                    "recipient_id", row.recipientId(),
                    "channel", row.channel()
            ));
        } catch (Exception ex) {
            updateAttempt(row.dispatchId(), row.recipientId(), row.channel(), attemptNo, AttemptStatus.FAILED, ex.getMessage());
            if (attemptNo >= properties.getMaxAttempts()) {
                updateDeliveryStatus(row.dispatchId(), row.recipientId(), DeliveryStatus.FAILED, ex.getMessage(), attemptNo, null);
            } else {
                updateDeliveryStatus(
                        row.dispatchId(),
                        row.recipientId(),
                        DeliveryStatus.RETRY,
                        ex.getMessage(),
                        attemptNo,
                        OffsetDateTime.now().plus(properties.getRetryBackoff())
                );
            }
        }
    }

    private List<InboxRow> lockInboxBatch() {
        return jdbc.query("""
                select message_id, aggregate_type, event_type, payload::text as payload_json, processing_status
                from nf.consumer_inbox_message
                where processing_status = :status
                  and aggregate_type = :aggregate_type
                  and event_type = :event_type
                order by received_at, message_id
                for update skip locked
                limit :limit
                """, new MapSqlParameterSource()
                .addValue("status", InboxStatus.NEW.dbValue())
                .addValue("aggregate_type", AGGREGATE_TYPE_MAIL_DISPATCH)
                .addValue("event_type", EVENT_TYPE_MAIL_DISPATCH_REQUESTED)
                .addValue("limit", properties.getInboxBatchSize()), (rs, rowNum) -> new InboxRow(
                UUID.fromString(rs.getString("message_id")),
                rs.getString("aggregate_type"),
                rs.getString("event_type"),
                rs.getString("payload_json"),
                InboxStatus.fromDb(rs.getString("processing_status"))
        ));
    }

    private List<MailDeliveryRow> lockDeliveryBatch() {
        return jdbc.query("""
                select dispatch_id, event_id, recipient_id, channel, status, email,
                       payload::text as payload_json, template_id, template_version,
                       idempotency_key, attempt_count, next_attempt_at
                from nf.mail_delivery
                where status in (:pending, :retry)
                  and (next_attempt_at is null or next_attempt_at <= :now_ts)
                order by created_at, dispatch_id, recipient_id
                for update skip locked
                limit :limit
                """, new MapSqlParameterSource()
                .addValue("pending", DeliveryStatus.PENDING.dbValue())
                .addValue("retry", DeliveryStatus.RETRY.dbValue())
                .addValue("now_ts", OffsetDateTime.now())
                .addValue("limit", properties.getDeliveryBatchSize()), (rs, rowNum) -> mapMailDeliveryRow(rs));
    }

    private void updateInboxStatus(UUID messageId, InboxStatus status, String errorMessage) {
        jdbc.update("""
                update nf.consumer_inbox_message
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

    private void createAttempt(UUID dispatchId,
                               String recipientId,
                               String channel,
                               int attemptNo,
                               AttemptStatus status,
                               String errorMessage) {
        try {
            jdbc.update("""
                    insert into nf.mail_delivery_attempt(
                      dispatch_id, recipient_id, channel, attempt_no, status, error_message, created_at
                    ) values (
                      :dispatch_id, :recipient_id, :channel, :attempt_no, :status, :error_message, :created_at
                    )
                    """, new MapSqlParameterSource()
                    .addValue("dispatch_id", dispatchId)
                    .addValue("recipient_id", recipientId)
                    .addValue("channel", channel)
                    .addValue("attempt_no", attemptNo)
                    .addValue("status", status.dbValue())
                    .addValue("error_message", truncate(errorMessage))
                    .addValue("created_at", OffsetDateTime.now()));
        } catch (DuplicateKeyException ignored) {
            log.debug("Attempt already exists dispatchId={}, recipientId={}, attemptNo={}", dispatchId, recipientId, attemptNo);
        }
    }

    private void updateAttempt(UUID dispatchId,
                               String recipientId,
                               String channel,
                               int attemptNo,
                               AttemptStatus status,
                               String errorMessage) {
        jdbc.update("""
                update nf.mail_delivery_attempt
                set status = :status,
                    error_message = :error_message,
                    finished_at = :finished_at
                where dispatch_id = :dispatch_id
                  and recipient_id = :recipient_id
                  and channel = :channel
                  and attempt_no = :attempt_no
                """, new MapSqlParameterSource()
                .addValue("status", status.dbValue())
                .addValue("error_message", truncate(errorMessage))
                .addValue("finished_at", OffsetDateTime.now())
                .addValue("dispatch_id", dispatchId)
                .addValue("recipient_id", recipientId)
                .addValue("channel", channel)
                .addValue("attempt_no", attemptNo));
    }

    private void updateDeliveryStatus(UUID dispatchId,
                                      String recipientId,
                                      DeliveryStatus status,
                                      @Nullable String errorMessage,
                                      int attemptCount,
                                      @Nullable OffsetDateTime nextAttemptAt) {
        jdbc.update("""
                update nf.mail_delivery
                set status = :status,
                    attempt_count = :attempt_count,
                    next_attempt_at = :next_attempt_at,
                    last_error = :last_error
                where dispatch_id = :dispatch_id
                  and recipient_id = :recipient_id
                  and channel = :channel
                """, new MapSqlParameterSource()
                .addValue("status", status.dbValue())
                .addValue("attempt_count", attemptCount)
                .addValue("next_attempt_at", nextAttemptAt)
                .addValue("last_error", truncate(errorMessage))
                .addValue("dispatch_id", dispatchId)
                .addValue("recipient_id", recipientId)
                .addValue("channel", CHANNEL_EMAIL));
    }

    private static MailDeliveryRow mapMailDeliveryRow(ResultSet rs) throws SQLException {
        return new MailDeliveryRow(
                UUID.fromString(rs.getString("dispatch_id")),
                UUID.fromString(rs.getString("event_id")),
                rs.getString("recipient_id"),
                rs.getString("channel"),
                DeliveryStatus.fromDb(rs.getString("status")),
                rs.getString("email"),
                rs.getString("payload_json"),
                rs.getString("template_id"),
                rs.getInt("template_version"),
                rs.getString("idempotency_key"),
                rs.getInt("attempt_count"),
                rs.getObject("next_attempt_at", OffsetDateTime.class)
        );
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private enum InboxStatus implements DbMappedEnum {
        NEW("NEW"),
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

    private enum DeliveryStatus implements DbMappedEnum {
        PENDING("MAIL_DELIVERY_STATUS_PENDING"),
        SENDING("MAIL_DELIVERY_STATUS_SENDING"),
        SENT("MAIL_DELIVERY_STATUS_SENT"),
        RETRY("MAIL_DELIVERY_STATUS_RETRY"),
        FAILED("MAIL_DELIVERY_STATUS_FAILED"),
        SKIPPED("MAIL_DELIVERY_STATUS_SKIPPED");

        private final String dbValue;

        DeliveryStatus(String dbValue) {
            this.dbValue = dbValue;
        }

        @Override
        public String dbValue() {
            return dbValue;
        }

        static DeliveryStatus fromDb(String value) {
            return DbMappedEnum.fromDb(values(), value, DeliveryStatus.class.getSimpleName());
        }
    }

    private enum AttemptStatus implements DbMappedEnum {
        STARTED("STARTED"),
        SUCCESS("SUCCESS"),
        FAILED("FAILED");

        private final String dbValue;

        AttemptStatus(String dbValue) {
            this.dbValue = dbValue;
        }

        @Override
        public String dbValue() {
            return dbValue;
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
            String aggregateType,
            String eventType,
            String payloadJson,
            InboxStatus status
    ) {
    }

    private record DispatchMessage(
            UUID dispatchId,
            UUID eventId,
            String preferredChannel,
            String templateId,
            int templateVersion,
            Map<String, Object> payload,
            List<String> recipientIds
    ) {
        private static DispatchMessage fromPayload(Map<String, Object> payload) {
            requireField(payload, "dispatch_id");
            requireField(payload, "event_id");
            requireField(payload, "preferred_channel");
            requireField(payload, "template_id");
            requireField(payload, "template_version");
            return new DispatchMessage(
                    UUID.fromString(String.valueOf(payload.get("dispatch_id"))),
                    UUID.fromString(String.valueOf(payload.get("event_id"))),
                    String.valueOf(payload.get("preferred_channel")),
                    String.valueOf(payload.get("template_id")),
                    Integer.parseInt(String.valueOf(payload.get("template_version"))),
                    MailInboxService.asMap(payload.get("payload")),
                    asStringList(payload.get("recipient_ids"))
            );
        }

        private static void requireField(Map<String, Object> payload, String fieldName) {
            if (!payload.containsKey(fieldName) || payload.get(fieldName) == null) {
                throw new IllegalArgumentException("Inbox payload missing required field: " + fieldName);
            }
        }
    }

    private record MailDeliveryRow(
            UUID dispatchId,
            UUID eventId,
            String recipientId,
            String channel,
            DeliveryStatus status,
            String email,
            String payloadJson,
            String templateId,
            int templateVersion,
            String idempotencyKey,
            int attemptCount,
            OffsetDateTime nextAttemptAt
    ) {
    }
}
