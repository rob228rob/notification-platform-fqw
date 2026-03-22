package ru.batoyan.vkr.notification.scheduler.delivery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "scheduler.delivery")
public class SchedulerDeliveryProperties {

    private String consumerTopic = "notification.mail.dispatches.scheduled";
    private String producerTopic = "notification.mail.dispatches";
    private int consumerConcurrency = 1;
    private Duration pollFixedDelay = Duration.ofSeconds(5);
    private int batchSize = 100;
    private Duration retryBackoff = Duration.ofMinutes(1);

    public String getConsumerTopic() {
        return consumerTopic;
    }

    public void setConsumerTopic(String consumerTopic) {
        this.consumerTopic = consumerTopic;
    }

    public String getProducerTopic() {
        return producerTopic;
    }

    public void setProducerTopic(String producerTopic) {
        this.producerTopic = producerTopic;
    }

    public int getConsumerConcurrency() {
        return consumerConcurrency;
    }

    public void setConsumerConcurrency(int consumerConcurrency) {
        this.consumerConcurrency = consumerConcurrency;
    }

    public Duration getPollFixedDelay() {
        return pollFixedDelay;
    }

    public void setPollFixedDelay(Duration pollFixedDelay) {
        this.pollFixedDelay = pollFixedDelay;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(Duration retryBackoff) {
        this.retryBackoff = retryBackoff;
    }
}
