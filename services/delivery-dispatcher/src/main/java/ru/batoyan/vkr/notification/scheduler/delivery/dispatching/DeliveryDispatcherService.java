package ru.batoyan.vkr.notification.scheduler.delivery.dispatching;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import ru.batoyan.vkr.notification.scheduler.delivery.config.SchedulerDeliveryProperties;
import ru.batoyan.vkr.notification.scheduler.delivery.scheduling.KafkaEnvelope;
import ru.notification.common.proto.v1.Channel;
import ru.notification.profile.proto.v1.RecipientChannelSettings;
import ru.notification.profile.proto.v1.RecipientProfile;

@Service
@RequiredArgsConstructor
public class DeliveryDispatcherService {

    private static final Logger LOG = LogManager.getLogger();

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ProfileConsentClient profileConsentClient;
    private final CancellationServiceClient cancellationServiceClient;
    private final SchedulerDeliveryProperties properties;

    public void routeDispatch(KafkaEnvelope envelope) {
        var dispatch = DispatchRequest.fromEnvelope(envelope);
        var cancellation = cancellationServiceClient.checkDeliveryAllowed(
                dispatch.dispatchId(),
                dispatch.eventId(),
                dispatch.clientId()
        );
        if (!cancellation.getAllowed()) {
            publishCanceledStatuses(dispatch, dispatch.recipientIds(), cancellation.getReason());
            LOG.info("Dispatch skipped because it is cancelled dispatchId={}", dispatch.dispatchId());
            return;
        }

        var profiles = profileConsentClient.getProfiles(dispatch.recipientIds());
        for (var recipientId : dispatch.recipientIds()) {
            var profile = profiles.get(recipientId);
            var destination = resolveDestination(profile, dispatch.preferredChannel());
            if (destination == null) {
                publishStatus(dispatch, recipientId, dispatch.preferredChannel(), "SKIPPED", "PROFILE_OR_CHANNEL_UNAVAILABLE");
                continue;
            }
            publishSenderCommand(dispatch, recipientId, destination);
        }
    }

    public void handleFallback(KafkaEnvelope envelope) {
        var fallback = FallbackRequest.fromEnvelope(envelope);
        if (fallback.fallbackDepth() >= properties.getMaxFallbackDepth()) {
            LOG.info("Fallback depth exhausted dispatchId={}, recipientId={}, depth={}",
                    fallback.dispatchId(), fallback.recipientId(), fallback.fallbackDepth());
            return;
        }
        if (!fallback.fallbackChannels().contains(Channel.CHANNEL_SMS.name())
                || fallback.visitedChannels().contains(Channel.CHANNEL_SMS.name())) {
            LOG.info("Fallback to SMS is not allowed dispatchId={}, recipientId={}",
                    fallback.dispatchId(), fallback.recipientId());
            return;
        }

        var profiles = profileConsentClient.getProfiles(List.of(fallback.recipientId()));
        var profile = profiles.get(fallback.recipientId());
        var destination = resolveDestination(profile, Channel.CHANNEL_SMS.name());
        if (destination == null) {
            LOG.info("Fallback destination unavailable dispatchId={}, recipientId={}",
                    fallback.dispatchId(), fallback.recipientId());
            return;
        }

        var command = new LinkedHashMap<String, Object>();
        command.put("dispatch_id", fallback.dispatchId());
        command.put("event_id", fallback.eventId());
        command.put("client_id", fallback.clientId());
        command.put("recipient_id", fallback.recipientId());
        command.put("channel", Channel.CHANNEL_SMS.name());
        command.put("destination", destination);
        command.put("template_id", fallback.templateId());
        command.put("template_version", fallback.templateVersion());
        command.put("payload", fallback.payload());
        command.put("fallback_depth", fallback.fallbackDepth() + 1);
        command.put("fallback_from_channel", fallback.channel());
        var visited = new ArrayList<>(fallback.visitedChannels());
        visited.add(Channel.CHANNEL_SMS.name());
        command.put("visited_channels", visited);
        command.put("fallback_channels", fallback.fallbackChannels());
        command.put("created_at", OffsetDateTime.now().toString());

        publishEnvelope(
                properties.getSmsTopic(),
                "sms_delivery_command",
                fallback.dispatchId() + ":" + fallback.recipientId() + ":" + Channel.CHANNEL_SMS.name(),
                "SmsDeliveryCommandRequested",
                command
        );
    }

    private void publishSenderCommand(DispatchRequest dispatch, String recipientId, String destination) {
        var command = new LinkedHashMap<String, Object>();
        command.put("dispatch_id", dispatch.dispatchId());
        command.put("event_id", dispatch.eventId());
        command.put("client_id", dispatch.clientId());
        command.put("recipient_id", recipientId);
        command.put("channel", dispatch.preferredChannel());
        command.put("destination", destination);
        command.put("template_id", dispatch.templateId());
        command.put("template_version", dispatch.templateVersion());
        command.put("payload", dispatch.payload());
        command.put("fallback_depth", 0);
        command.put("fallback_from_channel", "");
        command.put("visited_channels", List.of(dispatch.preferredChannel()));
        command.put("fallback_channels", dispatch.fallbackChannels());
        command.put("created_at", OffsetDateTime.now().toString());

        var topic = switch (dispatch.preferredChannel()) {
            case "CHANNEL_EMAIL" -> properties.getMailTopic();
            case "CHANNEL_SMS" -> properties.getSmsTopic();
            default -> throw new IllegalArgumentException("Unsupported channel " + dispatch.preferredChannel());
        };
        var aggregateType = dispatch.preferredChannel().equals(Channel.CHANNEL_EMAIL.name())
                ? "mail_delivery_command"
                : "sms_delivery_command";
        var eventType = dispatch.preferredChannel().equals(Channel.CHANNEL_EMAIL.name())
                ? "MailDeliveryCommandRequested"
                : "SmsDeliveryCommandRequested";
        publishEnvelope(topic, aggregateType, dispatch.dispatchId() + ":" + recipientId + ":" + dispatch.preferredChannel(), eventType, command);
    }

    private void publishCanceledStatuses(DispatchRequest dispatch, List<String> recipientIds, String reason) {
        for (var recipientId : recipientIds) {
            publishStatus(dispatch, recipientId, dispatch.preferredChannel(), "CANCELED", reason);
        }
    }

    private void publishStatus(DispatchRequest dispatch, String recipientId, String channel, String status, String errorMessage) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("dispatch_id", dispatch.dispatchId());
        payload.put("event_id", dispatch.eventId());
        payload.put("client_id", dispatch.clientId());
        payload.put("recipient_id", recipientId);
        payload.put("channel", channel);
        payload.put("status", statusDbValue(channel, status));
        payload.put("template_id", dispatch.templateId());
        payload.put("template_version", dispatch.templateVersion());
        payload.put("idempotency_key", dispatch.dispatchId() + ":" + recipientId + ":" + channel);
        payload.put("attempt_no", 0);
        payload.put("error_message", errorMessage == null ? "" : errorMessage);
        payload.put("occurred_at", OffsetDateTime.now().toString());

        var topic = channel.equals(Channel.CHANNEL_SMS.name())
                ? properties.getSmsStatusTopic()
                : properties.getMailStatusTopic();
        var aggregateType = channel.equals(Channel.CHANNEL_SMS.name()) ? "sms_delivery" : "mail_delivery";
        var eventType = channel.equals(Channel.CHANNEL_SMS.name())
                ? "SmsDeliveryStatusChanged"
                : "MailDeliveryStatusChanged";
        publishEnvelope(topic, aggregateType, dispatch.dispatchId() + ":" + recipientId + ":" + channel, eventType, payload);
    }

    private void publishEnvelope(String topic, String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        try {
            var envelope = new LinkedHashMap<String, Object>();
            envelope.put("outboxId", 0);
            envelope.put("aggregateType", aggregateType);
            envelope.put("aggregateId", aggregateId);
            envelope.put("eventType", eventType);
            envelope.put("payload", payload);
            envelope.put("headers", Map.of("message_id", aggregateId, "event_type", eventType));
            envelope.put("createdAt", OffsetDateTime.now().toString());
            kafkaTemplate.send(topic, aggregateId, objectMapper.writeValueAsString(envelope)).join();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to publish envelope to " + topic, ex);
        }
    }

    private String resolveDestination(RecipientProfile profile, String channelName) {
        if (profile == null || !profile.getActive()) {
            return null;
        }
        Channel channel;
        try {
            channel = Channel.valueOf(channelName);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        RecipientChannelSettings settings = null;
        for (var candidate : profile.getChannelsList()) {
            if (candidate.getChannel() == channel) {
                settings = candidate;
                break;
            }
        }
        if (settings == null || !settings.getEnabled() || settings.getBlacklisted() || settings.getDestination().isBlank()) {
            return null;
        }
        return settings.getDestination();
    }

    private String statusDbValue(String channel, String status) {
        var prefix = channel.equals(Channel.CHANNEL_SMS.name()) ? "SMS" : "MAIL";
        return switch (status) {
            case "SKIPPED" -> prefix + "_DELIVERY_STATUS_SKIPPED";
            case "CANCELED" -> prefix + "_DELIVERY_STATUS_CANCELED";
            default -> prefix + "_DELIVERY_STATUS_FAILED";
        };
    }

    private record DispatchRequest(
            String dispatchId,
            String eventId,
            String clientId,
            String preferredChannel,
            String templateId,
            int templateVersion,
            Map<String, Object> payload,
            List<String> recipientIds,
            List<String> fallbackChannels
    ) {
        private static DispatchRequest fromEnvelope(KafkaEnvelope envelope) {
            var payload = envelope.payload();
            return new DispatchRequest(
                    String.valueOf(payload.get("dispatch_id")),
                    String.valueOf(payload.get("event_id")),
                    String.valueOf(payload.get("client_id")),
                    String.valueOf(payload.get("preferred_channel")),
                    String.valueOf(payload.get("template_id")),
                    Integer.parseInt(String.valueOf(payload.get("template_version"))),
                    asMap(payload.get("payload")),
                    asList(payload.get("recipient_ids")),
                    asList(payload.get("fallback_channels"))
            );
        }
    }

    private record FallbackRequest(
            String dispatchId,
            String eventId,
            String clientId,
            String recipientId,
            String channel,
            String templateId,
            int templateVersion,
            Map<String, Object> payload,
            int fallbackDepth,
            List<String> visitedChannels,
            List<String> fallbackChannels
    ) {
        private static FallbackRequest fromEnvelope(KafkaEnvelope envelope) {
            var payload = envelope.payload();
            return new FallbackRequest(
                    String.valueOf(payload.get("dispatch_id")),
                    String.valueOf(payload.get("event_id")),
                    String.valueOf(payload.get("client_id")),
                    String.valueOf(payload.get("recipient_id")),
                    String.valueOf(payload.get("channel")),
                    String.valueOf(payload.get("template_id")),
                    Integer.parseInt(String.valueOf(payload.get("template_version"))),
                    asMap(payload.get("payload")),
                    Integer.parseInt(String.valueOf(payload.getOrDefault("fallback_depth", 0))),
                    asList(payload.get("visited_channels")),
                    asList(payload.get("fallback_channels"))
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Object value) {
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }
}
