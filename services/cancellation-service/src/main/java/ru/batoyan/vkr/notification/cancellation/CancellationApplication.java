package ru.batoyan.vkr.notification.cancellation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CancellationApplication {

    public static void main(String[] args) {
        SpringApplication.run(CancellationApplication.class, args);
    }
}
