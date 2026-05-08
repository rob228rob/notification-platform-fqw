package ru.batoyan.vkr.notification.scheduler.delivery.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Setter
@Getter
@ConfigurationProperties(prefix = "dispatcher.delivery")
public class SchedulerDeliveryProperties {
    private String dispatchTopic = "delivery.dispatcher";
    private String fallbackTopic = "delivery.fallback";
    private String mailTopic = "notification.mail.dispatches";
    private String smsTopic = "notification.sms.dispatches";
    private String mailStatusTopic = "notification.mail.delivery-statuses";
    private String smsStatusTopic = "notification.sms.delivery-statuses";
    private int consumerConcurrency = 1;
    private Duration pollFixedDelay = Duration.ofSeconds(5);
    private int batchSize = 100;
    private Duration retryBackoff = Duration.ofMinutes(1);
    private int maxFallbackDepth = 1;
}
