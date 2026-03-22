package ru.batoyan.vkr.notification.scheduler.delivery.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Setter
@Getter
@ConfigurationProperties(prefix = "scheduler.delivery")
public class SchedulerDeliveryProperties {
    private String consumerTopic = "notification.mail.dispatches.scheduled";
    private String producerTopic = "notification.mail.dispatches";
    private int consumerConcurrency = 1;
    private Duration pollFixedDelay = Duration.ofSeconds(5);
    private int batchSize = 100;
    private Duration retryBackoff = Duration.ofMinutes(1);

}
