package ru.batoyan.vkr.notification.sms.sender.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "outbox.relay")
public class OutboxRelayProperties {

    private boolean enabled = true;
    private Duration fixedDelay = Duration.ofSeconds(10);
    private Duration leaseDuration = Duration.ofSeconds(30);
    private int batchSize = 100;
    private String schema = "nf_sms";
    private String table = "outbox_message";
    private Topics topics = new Topics();
    private ProducerRetry producerRetry = new ProducerRetry();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getFixedDelay() { return fixedDelay; }
    public void setFixedDelay(Duration fixedDelay) { this.fixedDelay = fixedDelay; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }
    public String getTable() { return table; }
    public void setTable(String table) { this.table = table; }
    public Topics getTopics() { return topics; }
    public void setTopics(Topics topics) { this.topics = topics; }
    public ProducerRetry getProducerRetry() { return producerRetry; }
    public void setProducerRetry(ProducerRetry producerRetry) { this.producerRetry = producerRetry; }

    public static class Topics {
        private String events = "notification.facade.events";
        private String smsDispatches = "notification.sms.dispatches";
        private String smsDeliveryStatuses = "notification.sms.delivery-statuses";
        public String getEvents() { return events; }
        public void setEvents(String events) { this.events = events; }
        public String getSmsDispatches() { return smsDispatches; }
        public void setSmsDispatches(String smsDispatches) { this.smsDispatches = smsDispatches; }
        public String getSmsDeliveryStatuses() { return smsDeliveryStatuses; }
        public void setSmsDeliveryStatuses(String smsDeliveryStatuses) { this.smsDeliveryStatuses = smsDeliveryStatuses; }
    }

    public static class ProducerRetry {
        private int maxAttempts = 8;
        private Duration initialBackoff = Duration.ofMillis(100);
        private double multiplier = 2.0;
        private Duration maxBackoff = Duration.ofSeconds(5);
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Duration getInitialBackoff() { return initialBackoff; }
        public void setInitialBackoff(Duration initialBackoff) { this.initialBackoff = initialBackoff; }
        public double getMultiplier() { return multiplier; }
        public void setMultiplier(double multiplier) { this.multiplier = multiplier; }
        public Duration getMaxBackoff() { return maxBackoff; }
        public void setMaxBackoff(Duration maxBackoff) { this.maxBackoff = maxBackoff; }
    }
}
