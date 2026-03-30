package ru.batoyan.vkr.notification.sms.sender.services.kafka.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "delivery.sms.gateway")
public class LoggingSmsGatewayProperties {

    private boolean logOnly = true;
    private String sender = "notification-platform";

    public boolean isLogOnly() { return logOnly; }
    public void setLogOnly(boolean logOnly) { this.logOnly = logOnly; }
    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }
}
