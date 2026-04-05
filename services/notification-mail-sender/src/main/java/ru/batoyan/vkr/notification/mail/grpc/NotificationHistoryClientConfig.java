package ru.batoyan.vkr.notification.mail.grpc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class NotificationHistoryClientConfig {

    @Bean
    @ConfigurationProperties(prefix = "clients.notification-history")
    NotificationHistoryClientProperties notificationHistoryClientProperties() {
        return new NotificationHistoryClientProperties();
    }
}
