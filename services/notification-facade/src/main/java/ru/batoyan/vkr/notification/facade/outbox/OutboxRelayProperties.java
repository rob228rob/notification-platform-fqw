package ru.batoyan.vkr.notification.facade.outbox;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @author batoyan.rl
 * @since 03.03.2026
 */
@NullMarked
@Getter
@Validated
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "outbox.relay")
public class OutboxRelayProperties {
    private final boolean enabled;

    @NotNull
    private final Duration fixedDelay;

    @Min(1)
    private final int batchSize;

    @NotBlank
    private final String schema;

    @NotBlank
    private final String table;

    @NotNull
    private final Topics topics;

    @NotNull
    private final ProducerRetry producerRetry;

    public record Topics(
            @NotBlank String events,
            @NotBlank String dispatches,
            @NotBlank String mailDispatches
    ) {
    }

    public record ProducerRetry(
            @Min(1) int maxAttempts,
            @NotNull Duration initialBackoff,
            double multiplier,
            @NotNull Duration maxBackoff
    ) {
    }
}
