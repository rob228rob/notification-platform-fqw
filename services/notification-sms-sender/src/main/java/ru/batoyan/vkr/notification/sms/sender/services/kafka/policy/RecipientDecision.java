package ru.batoyan.vkr.notification.sms.sender.services.kafka.policy;

public record RecipientDecision(
        boolean allowed,
        String destination,
        String reasonCode
) {
}
