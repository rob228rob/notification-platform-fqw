package ru.batoyan.vkr.notification.sms.sender.services.kafka.policy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ru.batoyan.vkr.notification.sms.sender.services.kafka.Jsons;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsDeliveryPlanService {

    public static final String CHANNEL_SMS = "CHANNEL_SMS";
    private static final String STATUS_PENDING = "SMS_DELIVERY_STATUS_PENDING";
    private static final String STATUS_SKIPPED = "SMS_DELIVERY_STATUS_SKIPPED";

    private final NamedParameterJdbcTemplate jdbc;
    private final ProfileConsentClient profileConsentClient;

    public Map<String, RecipientDecision> evaluateRecipients(Collection<String> recipientIds) {
        return profileConsentClient.batchGetSmsDecisions(recipientIds);
    }

    public void createPendingDelivery(UUID dispatchId, UUID eventId, String clientId, String recipientId, String phone,
                                      String templateId, int templateVersion, Map<String, Object> payload) {
        try {
            jdbc.update("""
                    insert into nf_sms.sms_delivery(
                      dispatch_id, event_id, client_id, recipient_id, channel, status, phone,
                      template_id, template_version, payload, idempotency_key,
                      attempt_count, next_attempt_at, created_at
                    ) values (
                      :dispatch_id, :event_id, :client_id, :recipient_id, :channel, :status, :phone,
                      :template_id, :template_version, cast(:payload as jsonb), :idempotency_key,
                      0, :next_attempt_at, :created_at
                    )
                    """, new MapSqlParameterSource()
                    .addValue("dispatch_id", dispatchId)
                    .addValue("event_id", eventId)
                    .addValue("client_id", clientId)
                    .addValue("recipient_id", recipientId)
                    .addValue("channel", CHANNEL_SMS)
                    .addValue("status", STATUS_PENDING)
                    .addValue("phone", phone)
                    .addValue("template_id", templateId)
                    .addValue("template_version", templateVersion)
                    .addValue("payload", Jsons.write(payload))
                    .addValue("idempotency_key", deliveryIdempotencyKey(dispatchId, recipientId))
                    .addValue("next_attempt_at", OffsetDateTime.now())
                    .addValue("created_at", OffsetDateTime.now()));
        } catch (DuplicateKeyException ignored) {
            log.info("SMS delivery already exists dispatchId={}, recipientId={}", dispatchId, recipientId);
        }
    }

    public void saveSkippedDelivery(UUID dispatchId, UUID eventId, String clientId, String recipientId, String reasonCode,
                                    String phone, String templateId, int templateVersion, Map<String, Object> payload) {
        try {
            jdbc.update("""
                    insert into nf_sms.sms_delivery(
                      dispatch_id, event_id, client_id, recipient_id, channel, status, phone,
                      template_id, template_version, payload, rule_code, idempotency_key,
                      attempt_count, created_at
                    ) values (
                      :dispatch_id, :event_id, :client_id, :recipient_id, :channel, :status, :phone,
                      :template_id, :template_version, cast(:payload as jsonb), :rule_code, :idempotency_key,
                      0, :created_at
                    )
                    """, new MapSqlParameterSource()
                    .addValue("dispatch_id", dispatchId)
                    .addValue("event_id", eventId)
                    .addValue("client_id", clientId)
                    .addValue("recipient_id", recipientId)
                    .addValue("channel", CHANNEL_SMS)
                    .addValue("status", STATUS_SKIPPED)
                    .addValue("phone", phone == null ? "" : phone)
                    .addValue("template_id", templateId)
                    .addValue("template_version", templateVersion)
                    .addValue("payload", Jsons.write(payload))
                    .addValue("rule_code", reasonCode)
                    .addValue("idempotency_key", deliveryIdempotencyKey(dispatchId, recipientId))
                    .addValue("created_at", OffsetDateTime.now()));
        } catch (DuplicateKeyException ignored) {
            log.info("Skipped SMS delivery already exists dispatchId={}, recipientId={}", dispatchId, recipientId);
        }
    }

    private static String deliveryIdempotencyKey(UUID dispatchId, String recipientId) {
        return dispatchId + ":" + recipientId + ":" + CHANNEL_SMS;
    }
}
