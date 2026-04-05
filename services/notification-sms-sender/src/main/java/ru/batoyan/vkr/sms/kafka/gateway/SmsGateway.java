package ru.batoyan.vkr.sms.kafka.gateway;

import java.util.List;
import java.util.Map;

public interface SmsGateway {

    BatchSendResult sendBatch(List<SmsMessage> messages);

    record SmsMessage(String deliveryId, String idempotencyKey, String recipientId, String phone,
                      String templateId, int templateVersion, String payloadJson) {
    }

    record BatchSendResult(List<String> succeededDeliveryIds, Map<String, String> failedDeliveryErrors) {
    }
}
