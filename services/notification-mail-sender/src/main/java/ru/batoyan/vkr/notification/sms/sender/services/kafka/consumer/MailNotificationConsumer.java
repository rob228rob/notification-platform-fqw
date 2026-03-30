package ru.batoyan.vkr.notification.sms.sender.services.kafka.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import ru.batoyan.vkr.notification.sms.sender.services.kafka.MailInboxRepository;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class MailNotificationConsumer {

    private final MailInboxRepository mailInboxRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${outbox.relay.topics.mail-dispatches}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(
            @Payload String rawMessage,
            @Headers Map<String, Object> headers
    ) {
        log.info("[NOTIFICATION CONSUMER] received rawMessage={}, headers={}", rawMessage, headers);
        var msg = readMessage(rawMessage);
        var stored = mailInboxRepository.storeIncomingDispatchEvent(msg);
        log.info("Dispatch kafka event stored={}, aggregateId={}, eventType={}, headers={}",
                stored,
                MailInboxRepository.asString(msg.get("aggregateId")),
                MailInboxRepository.asString(msg.get("eventType")),
                headers);
    }

    private Map<String, Object> readMessage(String rawMessage) {
        try {
            var root = unwrapTextualNode(rawMessage);
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
        var current = objectMapper.readTree(rawMessage);
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
