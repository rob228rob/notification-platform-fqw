package ru.batoyan.vkr.notification.mail.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class ProviderGuardConfig {

    @Bean(destroyMethod = "shutdown")
    public Executor providerGuardExecutor() {
        return Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()));
    }
}
