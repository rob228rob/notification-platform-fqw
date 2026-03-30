package ru.batoyan.vkr.notification.sms.sender.services.kafka.policy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ProfileConsentClientConfig {

    @Bean
    @ConfigurationProperties(prefix = "clients.profile-consent")
    ProfileConsentClientProperties profileConsentClientProperties() {
        return new ProfileConsentClientProperties();
    }
}
