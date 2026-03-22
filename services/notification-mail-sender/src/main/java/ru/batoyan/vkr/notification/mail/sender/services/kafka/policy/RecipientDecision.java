package ru.batoyan.vkr.notification.mail.sender.services.kafka.policy;

public record RecipientDecision(
        boolean allowed,
        String reasonCode,
        String email
) {
    public static RecipientDecision allowed(String email) {
        return new RecipientDecision(true, null, email);
    }

    public static RecipientDecision denied(String reasonCode, String email) {
        return new RecipientDecision(false, reasonCode, email);
    }
}
