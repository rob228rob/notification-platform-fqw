package ru.batoyan.vkr.notification.sms.sender.services.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Map;

public final class Jsons {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private Jsons() {
    }

    public static String write(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to write json", ex);
        }
    }

    public static Map<String, Object> read(String value) {
        try {
            if (value == null || value.isBlank()) {
                return Collections.emptyMap();
            }
            return OBJECT_MAPPER.readValue(value, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read json", ex);
        }
    }
}
