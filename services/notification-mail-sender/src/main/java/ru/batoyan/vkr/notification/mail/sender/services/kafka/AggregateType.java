package ru.batoyan.vkr.notification.mail.sender.services.kafka;

import java.util.Arrays;

public enum AggregateType {
    NOTIFICATION_EVENT("notification_event"),
    MAIL_DISPATCH("mail_dispatch");

    private final String dbValue;

    AggregateType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static AggregateType fromDb(String value) {
        return Arrays.stream(values())
                .filter(type -> type.dbValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown AggregateType db value: " + value));
    }

    public boolean matches(String value) {
        return dbValue.equals(value);
    }
}
