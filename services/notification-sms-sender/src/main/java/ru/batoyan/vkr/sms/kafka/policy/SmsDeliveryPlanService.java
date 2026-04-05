package ru.batoyan.vkr.sms.kafka.policy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ru.batoyan.vkr.sms.kafka.Jsons;
import ru.notification.common.proto.v1.Channel;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
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
    private final NotificationHistoryClient notificationHistoryClient;
    private final SmsDeliveryProperties properties;

    public Map<String, RecipientDecision> evaluateRecipients(Collection<String> recipientIds) {
        log.info("SMS planning profile-consent batch check started recipients={}", recipientIds.size());
        var decisions = profileConsentClient.batchGetSmsDecisions(recipientIds);
        var allowed = decisions.values().stream().filter(RecipientDecision::allowed).count();
        var rejected = decisions.size() - allowed;
        log.info("SMS planning profile-consent batch check completed requested={}, resolved={}, allowed={}, rejected={}",
                recipientIds.size(), decisions.size(), allowed, rejected);
        return applyHistoryWindow(decisions);
    }

    private Map<String, RecipientDecision> applyHistoryWindow(Map<String, RecipientDecision> decisions) {
        if (!properties.historyCheckEnabled()) {
            return decisions;
        }

        var allowedRecipients = decisions.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().allowed())
                .map(Map.Entry::getKey)
                .toList();
        if (allowedRecipients.isEmpty()) {
            return decisions;
        }

        try {
            log.info("SMS planning history check started recipients={}, lookbackHours={}",
                    allowedRecipients.size(), properties.historyCheckLookbackHours());
            var response = notificationHistoryClient.batchGetRecipientDeliverySummaries(
                    allowedRecipients,
                    properties.historyCheckLookbackHours()
            );

            var summariesByRecipient = new LinkedHashMap<String, ru.notification.history.proto.v1.RecipientDeliverySummary>();
            response.getSummariesList().forEach(summary -> summariesByRecipient.put(summary.getRecipientId(), summary));

            var updated = new LinkedHashMap<String, RecipientDecision>(decisions);
            for (var recipientId : allowedRecipients) {
                var summary = summariesByRecipient.get(recipientId);
                if (summary == null) {
                    continue;
                }
                var channelSummary = summary.getChannelsList().stream()
                        .filter(channel -> channel.getChannel() == Channel.CHANNEL_SMS)
                        .findFirst()
                        .orElse(null);
                var total = channelSummary == null ? 0 : channelSummary.getTotalCount();
                log.info("SMS planning history check recipientId={}, successful={}, unsuccessful={}, total={}",
                        recipientId,
                        channelSummary == null ? 0 : channelSummary.getSuccessfulCount(),
                        channelSummary == null ? 0 : channelSummary.getUnsuccessfulCount(),
                        total);
                if (total >= properties.maxTotalDeliveriesInWindow()) {
                    var current = updated.get(recipientId);
                    updated.put(recipientId, new RecipientDecision(false, current.destination(), "DELIVERY_LIMIT_EXCEEDED"));
                }
            }
            return updated;
        } catch (Exception ex) {
            log.warn("SMS planning history check unavailable recipients={}, err={}. Continue fail-open.",
                    allowedRecipients.size(), ex.getMessage(), ex);
            return decisions;
        }
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
