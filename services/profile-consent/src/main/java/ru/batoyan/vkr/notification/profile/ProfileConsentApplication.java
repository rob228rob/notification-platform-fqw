package ru.batoyan.vkr.notification.profile;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ProfileConsentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProfileConsentApplication.class, args);
    }
}
