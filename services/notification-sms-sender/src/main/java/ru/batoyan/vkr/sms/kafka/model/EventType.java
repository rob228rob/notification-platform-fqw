package ru.batoyan.vkr.sms.kafka.model;

import java.util.Arrays;

public enum EventType {
    EVENT_CREATED("EventCreated"),
    SMS_DISPATCH_REQUESTED("SmsDispatchRequested");

    private final String dbValue;

    EventType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() { return dbValue; }
    public boolean matches(String value) { return dbValue.equals(value); }

    public static EventType fromDb(String value) {
        return Arrays.stream(values())
                .filter(type -> type.dbValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown EventType db value: " + value));
    }
}
