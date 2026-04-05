package ru.batoyan.vkr.sms.kafka.policy;

public record RecipientDecision(
        boolean allowed,
        String destination,
        String reasonCode
) {
}
