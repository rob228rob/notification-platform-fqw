package ru.batoyan.vkr.notification.mail.kafka.gateway;

import java.util.List;
import java.util.Map;

public interface MailGateway {

    BatchSendResult sendBatch(List<MailMessage> messages);

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

    record BatchSendResult(
            List<String> succeededDeliveryIds,
            Map<String, String> failedDeliveryErrors
    ) {
    }
}
