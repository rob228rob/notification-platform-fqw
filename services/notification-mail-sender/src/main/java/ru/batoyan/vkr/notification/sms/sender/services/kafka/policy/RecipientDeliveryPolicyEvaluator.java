package ru.batoyan.vkr.notification.sms.sender.services.kafka.policy;

public interface RecipientDeliveryPolicyEvaluator {

    RecipientDecision evaluateRecipient(String recipientId);
}
