package ru.batoyan.vkr.notification.mail.sender.services.kafka.gateway;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Validated
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "delivery.mail.gateway")
public class SpringMailGatewayProperties {

    @NotBlank
    private final String from;

    @NotBlank
    private final String subjectFallbackPrefix;

    private final boolean logOnly;
}
