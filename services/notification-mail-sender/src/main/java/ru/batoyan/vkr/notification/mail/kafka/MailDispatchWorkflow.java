package ru.batoyan.vkr.notification.mail.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.batoyan.vkr.notification.mail.kafka.gateway.MailGateway;
import ru.batoyan.vkr.notification.mail.kafka.model.AggregateType;
import ru.batoyan.vkr.notification.mail.kafka.model.EventType;
import ru.batoyan.vkr.notification.mail.kafka.policy.MailDeliveryPlanService;
import ru.batoyan.vkr.notification.mail.kafka.policy.MailDeliveryProperties;

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

    private final NamedParameterJdbcTemplate jdbc;
    private final MailGateway mailGateway;
    private final MailDeliveryProperties properties;
    private final MailDeliveryPlanService mailDeliveryPlanService;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedRateString = "${delivery.mail.inbox-fixed-delay}")
    public void processInboxTick() {
        if (!properties.isEnabled()) {
            return;
        }
        var processed = processInboxNotificationBatch();
        if (processed > 0) {
            log.info("Mail inbox worker processed {} record(s)", processed);
        }
    }

    @Scheduled(fixedRateString = "${delivery.mail.delivery-fixed-delay}")
    public void processDeliveryTick() {
        if (!properties.isEnabled()) {
            return;
        }
        var processed = processDeliveryBatch();
        if (processed > 0) {
            log.info("Mail delivery worker processed {} record(s)", processed);
        }
    }

    private int processInboxNotificationBatch() {
        var rows = transactionTemplate.execute(status -> claimInboxBatchTx());
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
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

    private List<InboxRow> claimInboxBatchTx() {
        var rows = lockInboxBatch();
        for (var row : rows) {
            updateInboxStatus(row.messageId(), InboxStatus.PROCESSING, null);
        }
        return rows;
    }

    public int processDeliveryBatch() {
        var rows = transactionTemplate.execute(status -> claimDeliveryBatchTx());
        if (rows == null || rows.isEmpty()) {
            log.debug("found nothing, couldnt claim mail deivery batch");
            return 0;
        }

        log.info("Mail delivery batch claimed size={}", rows.size());
        var messages = rows.stream()
                .map(row -> new MailGateway.MailMessage(
                        row.deliveryId(),
                        row.idempotencyKey(),
                        row.recipientId(),
                        row.email(),
                        row.templateId(),
                        row.templateVersion(),
                        row.payloadJson()
                ))
                .toList();

        var result = mailGateway.sendBatch(messages);
        finalizeBatch(rows, result);
        return rows.size();
    }

    private void handleInboxRow(InboxRow row) {
        // if there's occured unknown event then skippedd
        if (row.aggregateType() != AggregateType.MAIL_DISPATCH
                || row.eventType() != EventType.MAIL_DISPATCH_REQUESTED
        ) {
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

        dispatch.recipientIds()
                .forEach(recipientId -> {
                            var decision = mailDeliveryPlanService.evaluateRecipient(recipientId);
                            if (!decision.allowed()) {
                                mailDeliveryPlanService.saveSkippedDelivery(
                                        dispatch.dispatchId(),
                                        dispatch.eventId(),
                                        dispatch.clientId(),
                                        recipientId,
                                        decision.reasonCode(),
                                        decision.email(),
                                        dispatch.templateId(),
                                        dispatch.templateVersion(),
                                        dispatch.payload()
                                );
                                return;
                            }
                            mailDeliveryPlanService.createPendingDelivery(
                                    dispatch.dispatchId(),
                                    dispatch.eventId(),
                                    dispatch.clientId(),
                                    recipientId,
                                    decision.email(),
                                    dispatch.templateId(),
                                    dispatch.templateVersion(),
                                    dispatch.payload()
                            );
                        }
                );
    }

    private List<InboxRow> lockInboxBatch() {
        return jdbc.query("""
                select message_id, aggregate_type, event_type, payload::text as payload_json, processing_status
                from nf_mail.consumer_inbox_message
                where processing_status = :status
                  and aggregate_type = :aggregate_type
                  and event_type = :event_type
                order by received_at, message_id
                for update skip locked
                limit :limit
                """, new MapSqlParameterSource()
                .addValue("status", InboxStatus.NEW.dbValue())
                .addValue("aggregate_type", AggregateType.MAIL_DISPATCH.dbValue())
                .addValue("event_type", EventType.MAIL_DISPATCH_REQUESTED.dbValue())
                .addValue("limit", properties.getInboxBatchSize()), (rs, rowNum) -> new InboxRow(
                UUID.fromString(rs.getString("message_id")),
                AggregateType.fromDb(rs.getString("aggregate_type")),
                EventType.fromDb(rs.getString("event_type")),
                rs.getString("payload_json"),
                InboxStatus.fromDb(rs.getString("processing_status"))
        ));
    }

    private List<MailDeliveryRow> claimDeliveryBatchTx() {
        var rows = lockDeliveryBatch();
        for (var row : rows) {
            updateDeliveryStatus(row.dispatchId(), row.recipientId(), DeliveryStatus.SENDING, null, row.attemptCount(), row.nextAttemptAt());
            createAttempt(row.dispatchId(), row.recipientId(), row.channel(), row.nextAttemptNo(), AttemptStatus.STARTED, null);
        }
        return rows;
    }

    private List<MailDeliveryRow> lockDeliveryBatch() {
        return jdbc.query("""
                select dispatch_id, event_id, client_id, recipient_id, channel, status, email,
                       payload::text as payload_json, template_id, template_version,
                       idempotency_key, attempt_count, next_attempt_at
                from nf_mail.mail_delivery
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

    private void finalizeBatch(List<MailDeliveryRow> rows, MailGateway.BatchSendResult result) {
        transactionTemplate.executeWithoutResult(status -> finalizeBatchTx(rows, result));
        log.info("Mail delivery batch finalized size={}, succeeded={}, failed={}",
                rows.size(), result.succeededDeliveryIds().size(), result.failedDeliveryErrors().size());
    }

    private void finalizeBatchTx(List<MailDeliveryRow> rows, MailGateway.BatchSendResult result) {
        var failedByDeliveryId = result.failedDeliveryErrors();
        for (var row : rows) {
            var failure = failedByDeliveryId.get(row.deliveryId());
            if (failure == null) {
                markDeliverySuccessTx(row);
            } else {
                markDeliveryFailureTx(row, failure);
            }
        }
    }

    private void markDeliverySuccessTx(MailDeliveryRow row) {
        var sentAt = OffsetDateTime.now();
        updateAttempt(row.dispatchId(), row.recipientId(), row.channel(), row.nextAttemptNo(), AttemptStatus.SUCCESS, null);
        jdbc.update("""
                update nf_mail.mail_delivery
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
                "sent_at", sentAt,
                "dispatch_id", row.dispatchId(),
                "recipient_id", row.recipientId(),
                "channel", row.channel()
        ));
        enqueueMailDeliveryStatusOutbox(row, DeliveryStatus.SENT, null, null, sentAt, row.nextAttemptNo());
    }

    private void markDeliveryFailureTx(MailDeliveryRow row, String errorMessage) {
        updateAttempt(row.dispatchId(), row.recipientId(), row.channel(), row.nextAttemptNo(), AttemptStatus.FAILED, errorMessage);
        var nextAttemptNo = row.nextAttemptNo();
        if (nextAttemptNo >= properties.getMaxAttempts()) {
            updateDeliveryStatus(row.dispatchId(), row.recipientId(), DeliveryStatus.FAILED, errorMessage, nextAttemptNo, null);
            enqueueMailDeliveryStatusOutbox(row, DeliveryStatus.FAILED, errorMessage, null, OffsetDateTime.now(), nextAttemptNo);
        } else {
            var nextAttemptAt = OffsetDateTime.now().plus(properties.getRetryBackoff());
            updateDeliveryStatus(
                    row.dispatchId(),
                    row.recipientId(),
                    DeliveryStatus.RETRY,
                    errorMessage,
                    nextAttemptNo,
                    nextAttemptAt
            );
            enqueueMailDeliveryStatusOutbox(row, DeliveryStatus.RETRY, errorMessage, nextAttemptAt, OffsetDateTime.now(), nextAttemptNo);
        }
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

    private void createAttempt(UUID dispatchId,
                               String recipientId,
                               String channel,
                               int attemptNo,
                               AttemptStatus status,
                               String errorMessage) {
        try {
            jdbc.update("""
                    insert into nf_mail.mail_delivery_attempt(
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
                update nf_mail.mail_delivery_attempt
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
                update nf_mail.mail_delivery
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

    private void enqueueMailDeliveryStatusOutbox(MailDeliveryRow row,
                                                 DeliveryStatus deliveryStatus,
                                                 @Nullable String errorMessage,
                                                 @Nullable OffsetDateTime nextAttemptAt,
                                                 OffsetDateTime occurredAt,
                                                 int attemptNo) {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("dispatch_id", row.dispatchId().toString());
        payload.put("event_id", row.eventId().toString());
        payload.put("client_id", row.clientId());
        payload.put("recipient_id", row.recipientId());
        payload.put("email", row.email());
        payload.put("channel", row.channel());
        payload.put("status", deliveryStatus.dbValue());
        payload.put("template_id", row.templateId());
        payload.put("template_version", row.templateVersion());
        payload.put("idempotency_key", row.idempotencyKey());
        payload.put("attempt_no", attemptNo);
        payload.put("error_message", errorMessage);
        payload.put("next_attempt_at", nextAttemptAt == null ? null : nextAttemptAt.toString());
        payload.put("occurred_at", occurredAt.toString());

        var headers = Map.<String, Object>of(
                "message_id", row.deliveryId() + ":" + attemptNo + ":" + deliveryStatus.dbValue(),
                "event_type", "MailDeliveryStatusChanged"
        );

        jdbc.update("""
                insert into nf_mail.outbox_message(
                  aggregate_type, aggregate_id, event_type, payload, headers, created_at
                ) values (
                  :aggregate_type, :aggregate_id, :event_type, cast(:payload as jsonb), cast(:headers as jsonb), :created_at
                )
                """, new MapSqlParameterSource()
                .addValue("aggregate_type", "mail_delivery")
                .addValue("aggregate_id", row.deliveryId())
                .addValue("event_type", "MailDeliveryStatusChanged")
                .addValue("payload", Jsons.write(payload))
                .addValue("headers", Jsons.write(headers))
                .addValue("created_at", occurredAt));
        log.debug("Mail delivery status enqueued to outbox deliveryId={}, status={}",
                row.deliveryId(), deliveryStatus.dbValue());
    }

    private static MailDeliveryRow mapMailDeliveryRow(ResultSet rs) throws SQLException {
        return new MailDeliveryRow(
                UUID.fromString(rs.getString("dispatch_id")),
                UUID.fromString(rs.getString("event_id")),
                rs.getString("client_id"),
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
            AggregateType aggregateType,
            EventType eventType,
            String payloadJson,
            InboxStatus status
    ) {
    }

    private record DispatchMessage(
            UUID dispatchId,
            UUID eventId,
            String clientId,
            String preferredChannel,
            String templateId,
            int templateVersion,
            Map<String, Object> payload,
            List<String> recipientIds
    ) {
        private static DispatchMessage fromPayload(Map<String, Object> payload) {
            requireField(payload, "dispatch_id");
            requireField(payload, "event_id");
            requireField(payload, "client_id");
            requireField(payload, "preferred_channel");
            requireField(payload, "template_id");
            requireField(payload, "template_version");
            return new DispatchMessage(
                    UUID.fromString(String.valueOf(payload.get("dispatch_id"))),
                    UUID.fromString(String.valueOf(payload.get("event_id"))),
                    String.valueOf(payload.get("client_id")),
                    String.valueOf(payload.get("preferred_channel")),
                    String.valueOf(payload.get("template_id")),
                    Integer.parseInt(String.valueOf(payload.get("template_version"))),
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

    private record MailDeliveryRow(
            UUID dispatchId,
            UUID eventId,
            String clientId,
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
        private String deliveryId() {
            return dispatchId + ":" + recipientId + ":" + channel;
        }

        private int nextAttemptNo() {
            return attemptCount + 1;
        }
    }
}
