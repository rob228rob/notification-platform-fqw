package ru.batoyan.vkr.notification.sms.sender.services.kafka.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.batoyan.vkr.notification.sms.sender.services.kafka.policy.ProfileConsentClient;
import ru.notification.common.proto.v1.Channel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsProviderGuardService {

    private final ProfileConsentClient profileConsentClient;
    private final Executor providerGuardExecutor;

    public GuardResult validateBatch(List<SmsGateway.SmsMessage> messages) {
        if (messages.isEmpty()) {
            return new GuardResult(List.of(), Map.of());
        }

        var futures = messages.stream()
                .map(message -> CompletableFuture.supplyAsync(() -> validateMessage(message), providerGuardExecutor))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).join();

        var allowed = new ArrayList<SmsGateway.SmsMessage>(messages.size());
        var rejected = new LinkedHashMap<String, String>();
        for (var future : futures) {
            var result = (ValidationResult) future.join();
            if (result.allowed()) {
                allowed.add(result.message());
            } else {
                rejected.put(result.message().deliveryId(), result.reasonCode());
            }
        }
        return new GuardResult(List.copyOf(allowed), Map.copyOf(rejected));
    }

    private ValidationResult validateMessage(SmsGateway.SmsMessage message) {
        try {
            var response = profileConsentClient.checkRecipientChannel(message.recipientId(), Channel.CHANNEL_SMS);
            if (!response.getAllowed()) {
                return new ValidationResult(message, false, response.getReasonCode());
            }
            return new ValidationResult(message, true, "ALLOWED");
        } catch (Exception ex) {
            log.warn("SMS provider guard failed deliveryId={}, recipientId={}, err={}",
                    message.deliveryId(), message.recipientId(), ex.getMessage(), ex);
            return new ValidationResult(message, false, "PROFILE_CONSENT_UNAVAILABLE");
        }
    }

    public record GuardResult(List<SmsGateway.SmsMessage> allowedMessages, Map<String, String> rejectedDeliveries) {
    }

    private record ValidationResult(SmsGateway.SmsMessage message, boolean allowed, String reasonCode) {
    }
}
