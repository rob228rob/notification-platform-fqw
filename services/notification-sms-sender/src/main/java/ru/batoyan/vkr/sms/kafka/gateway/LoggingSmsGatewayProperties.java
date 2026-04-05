package ru.batoyan.vkr.sms.kafka.gateway;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@ConfigurationProperties(prefix = "delivery.sms.gateway")
public class LoggingSmsGatewayProperties {

    private boolean logOnly = true;
    private String sender = "notification-platform";

}