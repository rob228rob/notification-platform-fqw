package ru.batoyan.vkr.notification.sms.sender.services.kafka.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "delivery.notification-event")
public record NotificationEventProcessingProperties(
        boolean enabled,
        @Min(1) int inboxBatchSize,
        @NotNull Duration fixedDelay
) {
}
