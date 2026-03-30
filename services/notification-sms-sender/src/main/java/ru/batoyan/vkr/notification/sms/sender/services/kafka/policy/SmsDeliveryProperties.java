package ru.batoyan.vkr.notification.sms.sender.services.kafka.policy;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "delivery.sms")
public record SmsDeliveryProperties(
        boolean enabled,
        @Min(1) int inboxBatchSize,
        @Min(1) int deliveryBatchSize,
        @NotNull Duration inboxFixedDelay,
        @NotNull Duration deliveryFixedDelay,
        @Min(1) int maxAttempts,
        @NotNull Duration retryBackoff
) {
}
