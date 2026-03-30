package ru.batoyan.vkr.notification.sms.sender;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class NotificationSmsSenderApp {

    public static void main(String[] args) {
        SpringApplication.run(NotificationSmsSenderApp.class, args);
    }
}
