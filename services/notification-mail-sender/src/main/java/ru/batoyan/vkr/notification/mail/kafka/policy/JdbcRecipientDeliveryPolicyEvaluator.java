package ru.batoyan.vkr.notification.mail.kafka.policy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.batoyan.vkr.notification.mail.grpc.NotificationHistoryClient;
import ru.batoyan.vkr.notification.mail.grpc.ProfileConsentClient;
import ru.batoyan.vkr.notification.mail.grpc.RecipientDecision;
import ru.batoyan.vkr.notification.mail.grpc.RecipientDeliveryPolicyEvaluator;
import ru.notification.common.proto.v1.Channel;

@Slf4j
@Service
@RequiredArgsConstructor
public class JdbcRecipientDeliveryPolicyEvaluator implements RecipientDeliveryPolicyEvaluator {

    private final ProfileConsentClient profileConsentClient;
    private final NotificationHistoryClient notificationHistoryClient;
    private final MailDeliveryProperties properties;

    @Override
    public RecipientDecision evaluateRecipient(String recipientId) {
        log.info("MAIL planning profile-consent check started recipientId={}", recipientId);
        var response = profileConsentClient.checkRecipientChannel(recipientId, Channel.CHANNEL_EMAIL);
        if (!response.getAllowed()) {
            log.info("MAIL planning profile-consent check denied recipientId={}, reasonCode={}, preferredChannel={}, destination={}",
                    recipientId, response.getReasonCode(), response.getPreferredChannel(), response.getDestination());
            return RecipientDecision.denied(response.getReasonCode().name(), response.getDestination());
        }

        log.info("MAIL planning profile-consent check allowed recipientId={}, destination={}, preferredChannel={}",
                recipientId, response.getDestination(), response.getPreferredChannel());

        var historyDecision = evaluateHistoryWindow(recipientId, response.getDestination());
        if (historyDecision != null) {
            return historyDecision;
        }

        log.debug("Mail recipient allowed by profile-consent recipientId={}, destination={}",
                recipientId, response.getDestination());
        return RecipientDecision.allowed(response.getDestination());
    }

    private RecipientDecision evaluateHistoryWindow(String recipientId, String destination) {
        if (!properties.isHistoryCheckEnabled()) {
            return null;
        }

        var lookbackHours = Math.max(1, (int) Math.ceil(properties.getCountingWindow().toMinutes() / 60.0));
        try {
            log.info("MAIL planning history check started recipientId={}, lookbackHours={}", recipientId, lookbackHours);
            var response = notificationHistoryClient.getRecipientDeliverySummary(recipientId, lookbackHours);
            var summary = response.getSummary();
            var channelSummary = summary.getChannelsList().stream()
                    .filter(channel -> channel.getChannel() == Channel.CHANNEL_EMAIL)
                    .findFirst()
                    .orElse(null);
            var total = channelSummary == null ? 0 : channelSummary.getTotalCount();
            log.info("MAIL planning history check completed recipientId={}, successful={}, unsuccessful={}, total={}",
                    recipientId,
                    channelSummary == null ? 0 : channelSummary.getSuccessfulCount(),
                    channelSummary == null ? 0 : channelSummary.getUnsuccessfulCount(),
                    total);
            if (total >= properties.getDefaultMaxDeliveries()) {
                log.info("MAIL planning history check denied recipientId={}, total={}, max={}",
                        recipientId, total, properties.getDefaultMaxDeliveries());
                return RecipientDecision.denied("DELIVERY_LIMIT_EXCEEDED", destination);
            }
            return null;
        } catch (Exception ex) {
            log.warn("MAIL planning history check unavailable recipientId={}, err={}. Continue fail-open.",
                    recipientId, ex.getMessage(), ex);
            return null;
        }
    }
}
