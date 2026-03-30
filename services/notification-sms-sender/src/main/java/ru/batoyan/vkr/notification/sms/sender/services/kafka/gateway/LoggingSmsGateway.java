package ru.batoyan.vkr.notification.sms.sender.services.kafka.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoggingSmsGateway implements SmsGateway {

    private final LoggingSmsGatewayProperties properties;

    @Override
    public BatchSendResult sendBatch(List<SmsMessage> messages) {
        var succeeded = new ArrayList<String>();
        var failed = new LinkedHashMap<String, String>();
        for (var message : messages) {
            if (message.phone() == null || message.phone().isBlank()) {
                failed.put(message.deliveryId(), "PHONE_MISSING");
                continue;
            }
            log.info("SMS gateway send sender={}, recipientId={}, phone={}, templateId={}, templateVersion={}, payload={}",
                    properties.getSender(), message.recipientId(), message.phone(), message.templateId(), message.templateVersion(), message.payloadJson());
            succeeded.add(message.deliveryId());
        }
        return new BatchSendResult(List.copyOf(succeeded), failed);
    }
}
