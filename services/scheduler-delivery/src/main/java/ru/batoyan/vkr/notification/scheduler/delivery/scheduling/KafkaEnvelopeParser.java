package ru.batoyan.vkr.notification.scheduler.delivery.scheduling;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;

@Component
public class KafkaEnvelopeParser {

    private final ObjectMapper objectMapper;

    public KafkaEnvelopeParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public KafkaEnvelope parse(String rawMessage) {
        try {
            var root = unwrapTextualNode(rawMessage);
            if (!root.isObject()) {
                throw new IllegalArgumentException("Unsupported payload node type: " + root.getNodeType());
            }
            var map = objectMapper.convertValue(root, new TypeReference<Map<String, Object>>() {
            });
            return new KafkaEnvelope(
                    parseLong(map.get("outboxId")),
                    asString(map.get("aggregateType")),
                    asString(map.get("aggregateId")),
                    asString(map.get("eventType")),
                    asMap(map.get("payload")),
                    asMap(map.get("headers")),
                    parseCreatedAt(map.get("createdAt"))
            );
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to parse Kafka envelope: " + rawMessage, ex);
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

    private static long parseLong(Object value) {
        return value == null ? 0 : Long.parseLong(String.valueOf(value));
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static OffsetDateTime parseCreatedAt(Object value) {
        if (value == null) {
            return OffsetDateTime.now();
        }
        var text = String.valueOf(value);
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return OffsetDateTime.now();
        }
        return OffsetDateTime.parse(text);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }
}
