package ru.batoyan.vkr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;


@EnableScheduling
@EnableAsync
@EnableConfigurationProperties
@ConfigurationPropertiesScan(basePackages = "ru.batoyan.vkr")
@SpringBootApplication
public class NotificationMailSenderApp     {

    public static void main(String[] args) {
        SpringApplication.run(NotificationMailSenderApp.class, args);
    }

}
