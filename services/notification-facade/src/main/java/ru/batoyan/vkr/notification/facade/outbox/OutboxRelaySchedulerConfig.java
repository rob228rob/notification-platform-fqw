package ru.batoyan.vkr.notification.facade.outbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

@Configuration
public class OutboxRelaySchedulerConfig {

    @Bean(name = "outboxRelayTaskScheduler")
    public TaskScheduler outboxRelayTaskScheduler() {
        var scheduler = new SimpleAsyncTaskScheduler();
        scheduler.setThreadNamePrefix("outbox-relay-");
        scheduler.setVirtualThreads(true);
        scheduler.setConcurrencyLimit(8);
        return scheduler;
    }
}
