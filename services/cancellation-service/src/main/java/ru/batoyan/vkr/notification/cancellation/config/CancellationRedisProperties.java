package ru.batoyan.vkr.notification.cancellation.config;

import java.time.Duration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@ConfigurationProperties(prefix = "cancellation.redis")
public class CancellationRedisProperties {

    private String keyPrefix = "cancellation:dispatch:";
    private Duration ttl = Duration.ofMinutes(30);

}
