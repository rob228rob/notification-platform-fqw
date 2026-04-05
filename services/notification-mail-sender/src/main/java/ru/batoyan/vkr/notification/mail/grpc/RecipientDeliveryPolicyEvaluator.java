package ru.batoyan.vkr.notification.mail.grpc;

public interface RecipientDeliveryPolicyEvaluator {

    RecipientDecision evaluateRecipient(String recipientId);
}
