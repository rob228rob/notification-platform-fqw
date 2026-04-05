package ru.batoyan.vkr.notification.mail.kafka.policy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ru.batoyan.vkr.notification.mail.grpc.RecipientDecision;
import ru.batoyan.vkr.notification.mail.grpc.RecipientDeliveryPolicyEvaluator;
import ru.batoyan.vkr.notification.mail.kafka.Jsons;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailDeliveryPlanService {

    public static final String CHANNEL_EMAIL = "CHANNEL_EMAIL";

    private static final String STATUS_PENDING = "MAIL_DELIVERY_STATUS_PENDING";
    private static final String STATUS_SKIPPED = "MAIL_DELIVERY_STATUS_SKIPPED";

    private final NamedParameterJdbcTemplate jdbc;
    private final RecipientDeliveryPolicyEvaluator recipientPolicyEvaluator;

    public RecipientDecision evaluateRecipient(String recipientId) {
        return recipientPolicyEvaluator.evaluateRecipient(recipientId);
    }

    public void createPendingDelivery(UUID dispatchId,
                                      UUID eventId,
                                      String clientId,
                                      String recipientId,
                                      String email,
                                      String templateId,
                                      int templateVersion,
                                      Map<String, Object> payload) {
        try {
            jdbc.update("""
                    insert into nf_mail.mail_delivery(
                      dispatch_id, event_id, client_id, recipient_id, channel, status, email,
                      template_id, template_version, payload, idempotency_key,
                      attempt_count, next_attempt_at, created_at
                    ) values (
                      :dispatch_id, :event_id, :client_id, :recipient_id, :channel, :status, :email,
                      :template_id, :template_version, cast(:payload as jsonb), :idempotency_key,
                      0, :next_attempt_at, :created_at
                    )
                    """, new MapSqlParameterSource()
                    .addValue("dispatch_id", dispatchId)
                    .addValue("event_id", eventId)
                    .addValue("client_id", clientId)
                    .addValue("recipient_id", recipientId)
                    .addValue("channel", CHANNEL_EMAIL)
                    .addValue("status", STATUS_PENDING)
                    .addValue("email", email)
                    .addValue("template_id", templateId)
                    .addValue("template_version", templateVersion)
                    .addValue("payload", Jsons.write(payload))
                    .addValue("idempotency_key", deliveryIdempotencyKey(dispatchId, recipientId))
                    .addValue("next_attempt_at", OffsetDateTime.now())
                    .addValue("created_at", OffsetDateTime.now()));
            log.info("Mail delivery created dispatchId={}, eventId={}, clientId={}, recipientId={}, templateId={}",
                    dispatchId, eventId, clientId, recipientId, templateId);
        } catch (DuplicateKeyException ignored) {
            log.info("Mail delivery already exists dispatchId={}, eventId={}, clientId={}, recipientId={}",
                    dispatchId, eventId, clientId, recipientId);
        }
    }

    public void saveSkippedDelivery(UUID dispatchId,
                                    UUID eventId,
                                    String clientId,
                                    String recipientId,
                                    String reasonCode,
                                    String email,
                                    String templateId,
                                    int templateVersion,
                                    Map<String, Object> payload) {
        try {
            jdbc.update("""
                    insert into nf_mail.mail_delivery(
                      dispatch_id, event_id, client_id, recipient_id, channel, status, email,
                      template_id, template_version, payload, rule_code, idempotency_key,
                      attempt_count, created_at
                    ) values (
                      :dispatch_id, :event_id, :client_id, :recipient_id, :channel, :status, :email,
                      :template_id, :template_version, cast(:payload as jsonb), :rule_code, :idempotency_key,
                      0, :created_at
                    )
                    """, new MapSqlParameterSource()
                    .addValue("dispatch_id", dispatchId)
                    .addValue("event_id", eventId)
                    .addValue("client_id", clientId)
                    .addValue("recipient_id", recipientId)
                    .addValue("channel", CHANNEL_EMAIL)
                    .addValue("status", STATUS_SKIPPED)
                    .addValue("email", email == null ? "" : email)
                    .addValue("template_id", templateId)
                    .addValue("template_version", templateVersion)
                    .addValue("payload", Jsons.write(payload))
                    .addValue("rule_code", reasonCode)
                    .addValue("idempotency_key", deliveryIdempotencyKey(dispatchId, recipientId))
                    .addValue("created_at", OffsetDateTime.now()));
            log.info("Mail delivery skipped dispatchId={}, eventId={}, clientId={}, recipientId={}, reasonCode={}",
                    dispatchId, eventId, clientId, recipientId, reasonCode);
        } catch (DuplicateKeyException ignored) {
            log.info("Skipped mail delivery already exists dispatchId={}, eventId={}, clientId={}, recipientId={}",
                    dispatchId, eventId, clientId, recipientId);
        }
    }

    private static String deliveryIdempotencyKey(UUID dispatchId, String recipientId) {
        return dispatchId + ":" + recipientId + ":" + CHANNEL_EMAIL;
    }
}
