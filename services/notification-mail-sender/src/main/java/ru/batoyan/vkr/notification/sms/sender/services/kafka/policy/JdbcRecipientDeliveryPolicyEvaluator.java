package ru.batoyan.vkr.notification.sms.sender.services.kafka.policy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class JdbcRecipientDeliveryPolicyEvaluator implements RecipientDeliveryPolicyEvaluator {

    private static final String STATUS_PENDING = "MAIL_DELIVERY_STATUS_PENDING";
    private static final String STATUS_SENDING = "MAIL_DELIVERY_STATUS_SENDING";
    private static final String STATUS_SENT = "MAIL_DELIVERY_STATUS_SENT";
    private static final String STATUS_RETRY = "MAIL_DELIVERY_STATUS_RETRY";
    private static final String DEFAULT_EMAIL_DOMAIN = "dev.local";

    private final NamedParameterJdbcTemplate jdbc;
    private final MailDeliveryProperties properties;

    @Override
    public RecipientDecision evaluateRecipient(String recipientId) {
        var settings = findRecipientSettings(recipientId)
                .orElseGet(() -> defaultRecipientSettings(recipientId));
        if (!settings.active()) {
            return RecipientDecision.denied("RECIPIENT_INACTIVE", settings.email());
        }
        if (!settings.emailConsent()) {
            return RecipientDecision.denied("EMAIL_CONSENT_MISSING", settings.email());
        }

        long alreadyScheduled = jdbc.queryForObject("""
                select count(*)
                from nf_mail.mail_delivery
                where recipient_id = :recipient_id
                  and channel = :channel
                  and created_at >= :from_ts
                  and status in (:pending, :sending, :sent, :retry)
                """, new MapSqlParameterSource()
                .addValue("recipient_id", recipientId)
                .addValue("channel", MailDeliveryPlanService.CHANNEL_EMAIL)
                .addValue("from_ts", OffsetDateTime.now().minus(properties.getCountingWindow()))
                .addValue("pending", STATUS_PENDING)
                .addValue("sending", STATUS_SENDING)
                .addValue("sent", STATUS_SENT)
                .addValue("retry", STATUS_RETRY), Long.class);

        var maxDeliveries = settings.maxDeliveriesPerDay() > 0
                ? settings.maxDeliveriesPerDay()
                : properties.getDefaultMaxDeliveries();
        if (alreadyScheduled >= maxDeliveries) {
            return RecipientDecision.denied("DELIVERY_LIMIT_EXCEEDED", settings.email());
        }
        return RecipientDecision.allowed(settings.email());
    }

    private Optional<RecipientSettings> findRecipientSettings(String recipientId) {
        var rows = jdbc.query("""
                select recipient_id, email, email_consent, active, max_deliveries_per_day
                from nf_mail.recipient_mail_settings
                where recipient_id = :recipient_id
                """, Map.of("recipient_id", recipientId), (rs, rowNum) -> new RecipientSettings(
                rs.getString("recipient_id"),
                rs.getString("email"),
                rs.getBoolean("email_consent"),
                rs.getBoolean("active"),
                rs.getInt("max_deliveries_per_day")
        ));
        return rows.stream().findFirst();
    }

    private RecipientSettings defaultRecipientSettings(String recipientId) {
        var email = recipientId + "@" + DEFAULT_EMAIL_DOMAIN;
        var settings = new RecipientSettings(
                recipientId,
                email,
                true,
                true,
                properties.getDefaultMaxDeliveries()
        );
        log.warn("Recipient settings not found, using defaults recipientId={}, email={}, maxDeliveriesPerDay={}",
                recipientId, email, settings.maxDeliveriesPerDay());
        return settings;
    }

    private record RecipientSettings(
            String recipientId,
            String email,
            boolean emailConsent,
            boolean active,
            int maxDeliveriesPerDay
    ) {
    }
}
