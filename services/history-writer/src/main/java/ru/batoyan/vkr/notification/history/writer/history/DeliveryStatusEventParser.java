package ru.batoyan.vkr.notification.history.writer.history;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;

@Component
public class DeliveryStatusEventParser {

    private final ObjectMapper objectMapper;

    public DeliveryStatusEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DeliveryStatusEvent parse(String rawMessage) {
        try {
            var root = unwrapTextualNode(rawMessage);
            if (!root.isObject()) {
                throw new IllegalArgumentException("Unsupported payload node type: " + root.getNodeType());
            }
            var map = objectMapper.convertValue(root, new TypeReference<Map<String, Object>>() {
            });
            var createdAt = OffsetDateTime.parse(String.valueOf(map.get("createdAt")));
            return new DeliveryStatusEvent(
                    Long.parseLong(String.valueOf(map.get("outboxId"))),
                    String.valueOf(map.get("aggregateType")),
                    String.valueOf(map.get("aggregateId")),
                    String.valueOf(map.get("eventType")),
                    asMap(map.get("payload")),
                    asMap(map.get("headers")),
                    createdAt
            );
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to parse delivery status event: " + rawMessage, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
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
