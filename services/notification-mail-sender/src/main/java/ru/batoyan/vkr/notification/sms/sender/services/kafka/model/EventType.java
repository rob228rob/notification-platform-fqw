package ru.batoyan.vkr.notification.sms.sender.services.kafka.model;

import java.util.Arrays;

public enum EventType {
    EVENT_CREATED("EventCreated"),
    MAIL_DISPATCH_REQUESTED("MailDispatchRequested");

    private final String dbValue;

    EventType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static EventType fromDb(String value) {
        return Arrays.stream(values())
                .filter(type -> type.dbValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown EventType db value: " + value));
    }

    public boolean matches(String value) {
        return dbValue.equals(value);
    }
}
