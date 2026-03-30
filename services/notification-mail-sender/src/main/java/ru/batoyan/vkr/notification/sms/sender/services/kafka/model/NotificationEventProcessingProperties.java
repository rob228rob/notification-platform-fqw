package ru.batoyan.vkr.notification.sms.sender.services.kafka.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Validated
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "delivery.notification-event")
public class NotificationEventProcessingProperties {

    private final boolean enabled;

    @Min(1)
    private final int inboxBatchSize;

    @NotNull
    private final Duration fixedDelay;
}
