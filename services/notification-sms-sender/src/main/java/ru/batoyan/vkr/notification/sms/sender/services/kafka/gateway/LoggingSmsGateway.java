package ru.batoyan.vkr.notification.sms.sender.services.kafka.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoggingSmsGateway implements SmsGateway {

    private final LoggingSmsGatewayProperties properties;
    private final SmsProviderGuardService providerGuardService;

    @Override
    public BatchSendResult sendBatch(List<SmsMessage> messages) {
        if (messages.isEmpty()) {
            return new BatchSendResult(List.of(), Map.of());
        }

        var guardResult = providerGuardService.validateBatch(messages);
        var guardedMessages = guardResult.allowedMessages();
        var succeeded = new ArrayList<String>();
        var failed = new LinkedHashMap<String, String>(guardResult.rejectedDeliveries());
        for (var message : guardedMessages) {
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
