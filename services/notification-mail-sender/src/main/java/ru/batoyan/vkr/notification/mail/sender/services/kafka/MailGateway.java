package ru.batoyan.vkr.notification.mail.sender.services.kafka;

public interface MailGateway {

    void send(MailMessage message);

    record MailMessage(
            String deliveryId,
            String idempotencyKey,
            String recipientId,
            String email,
            String templateId,
            int templateVersion,
            String payloadJson
    ) {
    }
}
