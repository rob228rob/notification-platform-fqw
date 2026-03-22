package ru.batoyan.vkr.notification.mail.sender.services.kafka.policy;

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
@ConfigurationProperties(prefix = "delivery.mail")
public class MailDeliveryProperties {

    private final boolean enabled;

    @Min(1)
    private final int inboxBatchSize;

    @Min(1)
    private final int deliveryBatchSize;

    @NotNull
    private final Duration inboxFixedDelay;

    @NotNull
    private final Duration deliveryFixedDelay;

    @NotNull
    private final Duration countingWindow;

    @Min(1)
    private final int defaultMaxDeliveries;

    @Min(1)
    private final int maxAttempts;

    @NotNull
    private final Duration retryBackoff;
}
