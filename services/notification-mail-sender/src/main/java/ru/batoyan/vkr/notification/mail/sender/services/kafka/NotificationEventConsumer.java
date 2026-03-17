package ru.batoyan.vkr.notification.mail.sender.services.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {

    private final MailInboxRepository mailInboxRepo;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${outbox.relay.topics.events}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(@Payload String rawMessage,
                          @Headers Map<String, Object> headers) {
        log.info("[NOTIFICATION EVENT CONSUMER] received rawMessage={}, headers={}", rawMessage, headers);
        var msg = readMessage(rawMessage);
        var stored = mailInboxRepo.storeIncomingNotificationEvent(msg);
        log.info("Notification event stored={}, aggregateId={}, eventType={}, headers={}",
                stored,
                MailInboxRepository.asString(msg.get("aggregateId")),
                MailInboxRepository.asString(msg.get("eventType")),
                headers);
    }

    private Map<String, Object> readMessage(String rawMessage) {
        try {
            JsonNode root = unwrapTextualNode(rawMessage);
            if (!root.isObject()) {
                throw new IllegalArgumentException("Unsupported kafka payload node type: " + root.getNodeType());
            }
            return objectMapper.convertValue(root, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize kafka payload: " + rawMessage, e);
        }
    }

    private JsonNode unwrapTextualNode(String rawMessage) throws Exception {
        JsonNode current = objectMapper.readTree(rawMessage);
        int depth = 0;
        while (current.isTextual()) {
            depth++;
            if (depth > 5) {
                throw new IllegalArgumentException("Kafka payload nesting depth exceeded");
            }
            current = objectMapper.readTree(current.asText());
        }
        return current;
    }
}
