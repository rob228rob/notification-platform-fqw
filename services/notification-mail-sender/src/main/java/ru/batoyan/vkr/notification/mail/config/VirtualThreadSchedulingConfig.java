package ru.batoyan.vkr.notification.mail.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

@Configuration
public class VirtualThreadSchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        var scheduler = new SimpleAsyncTaskScheduler();
        scheduler.setThreadNamePrefix("mail-scheduler-");
        scheduler.setVirtualThreads(true);
        scheduler.setConcurrencyLimit(256);
        return scheduler;
    }
}
