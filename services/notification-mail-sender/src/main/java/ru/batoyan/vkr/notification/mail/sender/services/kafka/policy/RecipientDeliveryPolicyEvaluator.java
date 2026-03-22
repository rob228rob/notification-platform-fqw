package ru.batoyan.vkr.notification.mail.sender.services.kafka.policy;

public interface RecipientDeliveryPolicyEvaluator {

    RecipientDecision evaluateRecipient(String recipientId);
}
