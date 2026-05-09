package ru.batoyan.vkr.notification.profile.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@ConfigurationProperties(prefix = "app.profile-consent.storage")
public class ProfileConsentStorageProperties {

    private String type = "postgres";
}
