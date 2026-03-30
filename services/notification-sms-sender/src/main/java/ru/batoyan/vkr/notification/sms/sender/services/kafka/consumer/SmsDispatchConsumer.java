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
import ru.batoyan.vkr.notification.sms.sender.services.kafka.SmsInboxRepository;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmsDispatchConsumer {

    private final SmsInboxRepository inboxRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "${outbox.relay.topics.sms-dispatches}", groupId = "${spring.kafka.consumer.group-id}")
    public void onMessage(@Payload String rawMessage, @Headers Map<String, Object> headers) {
        var message = readMessage(rawMessage);
        var stored = inboxRepository.storeIncomingDispatchEvent(message);
        log.info("SMS dispatch stored={}, aggregateId={}, headers={}", stored, message.get("aggregateId"), headers);
    }

    private Map<String, Object> readMessage(String rawMessage) {
        try {
            JsonNode root = unwrapTextualNode(rawMessage);
            if (!root.isObject()) {
                throw new IllegalArgumentException("Unsupported kafka payload node type: " + root.getNodeType());
            }
            return objectMapper.convertValue(root, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to deserialize kafka payload: " + rawMessage, ex);
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
