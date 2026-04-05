package ru.batoyan.vkr.notification.profile.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@ConfigurationProperties(prefix = "profile-consent.redis")
public class ProfileConsentRedisProperties {

    private String keyPrefix = "profile-consent:recipient:";
}
