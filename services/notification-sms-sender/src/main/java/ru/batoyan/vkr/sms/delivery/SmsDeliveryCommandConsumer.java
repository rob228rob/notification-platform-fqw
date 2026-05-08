package ru.batoyan.vkr.sms.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import ru.batoyan.vkr.sms.kafka.Jsons;
import ru.batoyan.vkr.sms.kafka.gateway.SmsGateway;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmsDeliveryCommandConsumer {

    private final ObjectMapper objectMapper;
    private final SmsGateway smsGateway;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SmsRedisDedupService dedupService;
    private final SmsCancellationClient cancellationClient;

    @Value("${delivery.sms.max-attempts:3}")
    private int maxAttempts;

    @Value("${delivery.sms.retry-backoff:PT5M}")
    private Duration retryBackoff;

    @Value("${delivery.sms.dedup-ttl:PT6H}")
    private Duration dedupTtl;

    @Value("${outbox.relay.topics.sms-delivery-statuses:notification.sms.delivery-statuses}")
    private String statusTopic;

    @KafkaListener(
            topics = "${outbox.relay.topics.sms-dispatches}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(@Payload String rawMessage) {
        var envelope = readEnvelope(rawMessage);
        var command = SmsCommand.fromEnvelope(envelope);
        var messageId = envelope.aggregateId();
        if (!dedupService.tryAcquire(messageId, dedupTtl)) {
            log.info("SMS command deduplicated aggregateId={}", messageId);
            return;
        }

        var completed = false;
        try {
            for (var attempt = 1; attempt <= maxAttempts; attempt++) {
                var check = cancellationClient.checkDeliveryAllowed(command.dispatchId(), command.eventId(), command.clientId());
                if (!check.getAllowed()) {
                    publishStatus(command, "SMS_DELIVERY_STATUS_CANCELED", attempt, check.getReason());
                    completed = true;
                    return;
                }

                var result = smsGateway.sendBatch(List.of(new SmsGateway.SmsMessage(
                        command.deliveryId(),
                        command.idempotencyKey(),
                        command.recipientId(),
                        command.destination(),
                        command.templateId(),
                        command.templateVersion(),
                        Jsons.write(command.payload())
                )));
                if (!result.failedDeliveryErrors().containsKey(command.deliveryId())) {
                    publishStatus(command, "SMS_DELIVERY_STATUS_SENT", attempt, null);
                    completed = true;
                    return;
                }

                var error = result.failedDeliveryErrors().get(command.deliveryId());
                if (attempt < maxAttempts) {
                    publishStatus(command, "SMS_DELIVERY_STATUS_RETRY", attempt, error);
                    pauseBeforeRetry();
                    continue;
                }
                publishStatus(command, "SMS_DELIVERY_STATUS_FAILED", attempt, error);
                completed = true;
            }
        } catch (Exception ex) {
            dedupService.release(messageId);
            throw new IllegalStateException("SMS command processing failed", ex);
        } finally {
            if (!completed) {
                dedupService.release(messageId);
            }
        }
    }

    private void publishStatus(SmsCommand command, String status, int attemptNo, String errorMessage) throws Exception {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("dispatch_id", command.dispatchId());
        payload.put("event_id", command.eventId());
        payload.put("client_id", command.clientId());
        payload.put("recipient_id", command.recipientId());
        payload.put("phone", command.destination());
        payload.put("channel", command.channel());
        payload.put("status", status);
        payload.put("template_id", command.templateId());
        payload.put("template_version", command.templateVersion());
        payload.put("idempotency_key", command.idempotencyKey());
        payload.put("attempt_no", attemptNo);
        payload.put("error_message", errorMessage == null ? "" : errorMessage);
        payload.put("occurred_at", OffsetDateTime.now().toString());
        publishEnvelope(statusTopic, "sms_delivery", command.deliveryId(), "SmsDeliveryStatusChanged", payload);
    }

    private void publishEnvelope(String topic, String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) throws Exception {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("outboxId", 0);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId);
        envelope.put("eventType", eventType);
        envelope.put("payload", payload);
        envelope.put("headers", Map.of("message_id", aggregateId, "event_type", eventType));
        envelope.put("createdAt", OffsetDateTime.now().toString());
        kafkaTemplate.send(topic, aggregateId, objectMapper.writeValueAsString(envelope)).join();
    }

    private void pauseBeforeRetry() {
        var millis = Math.min(retryBackoff.toMillis(), 1000L);
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private PublisherEnvelope readEnvelope(String rawMessage) {
        try {
            JsonNode root = unwrap(rawMessage);
            return objectMapper.convertValue(root, PublisherEnvelope.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to deserialize sms command", ex);
        }
    }

    private JsonNode unwrap(String rawMessage) throws Exception {
        var current = objectMapper.readTree(rawMessage);
        var depth = 0;
        while (current.isTextual()) {
            depth++;
            if (depth > 5) {
                throw new IllegalArgumentException("Kafka payload nesting depth exceeded");
            }
            current = objectMapper.readTree(current.asText());
        }
        return current;
    }

    private record PublisherEnvelope(
            long outboxId,
            String aggregateType,
            String aggregateId,
            String eventType,
            Map<String, Object> payload,
            Map<String, Object> headers,
            String createdAt
    ) {
    }

    private record SmsCommand(
            String dispatchId,
            String eventId,
            String clientId,
            String recipientId,
            String channel,
            String destination,
            String templateId,
            int templateVersion,
            Map<String, Object> payload
    ) {
        private static SmsCommand fromEnvelope(PublisherEnvelope envelope) {
            var payload = envelope.payload();
            return new SmsCommand(
                    String.valueOf(payload.get("dispatch_id")),
                    String.valueOf(payload.get("event_id")),
                    String.valueOf(payload.get("client_id")),
                    String.valueOf(payload.get("recipient_id")),
                    String.valueOf(payload.get("channel")),
                    String.valueOf(payload.get("destination")),
                    String.valueOf(payload.get("template_id")),
                    Integer.parseInt(String.valueOf(payload.get("template_version"))),
                    asMap(payload.get("payload"))
            );
        }

        private String idempotencyKey() {
            return deliveryId();
        }

        private String deliveryId() {
            return dispatchId + ":" + recipientId + ":" + channel;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
    }
}
