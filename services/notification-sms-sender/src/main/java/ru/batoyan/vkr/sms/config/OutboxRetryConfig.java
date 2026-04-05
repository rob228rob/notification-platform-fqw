package ru.batoyan.vkr.sms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import ru.batoyan.vkr.sms.outbox.OutboxRelayProperties;

@Configuration
public class OutboxRetryConfig {

    @Bean
    public RetryTemplate outboxRetryTemplate(OutboxRelayProperties properties) {
        var retry = properties.getProducerRetry();
        var template = new RetryTemplate();
        template.setRetryPolicy(new SimpleRetryPolicy(retry.getMaxAttempts()));
        var backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(retry.getInitialBackoff().toMillis());
        backoff.setMultiplier(retry.getMultiplier());
        backoff.setMaxInterval(retry.getMaxBackoff().toMillis());
        template.setBackOffPolicy(backoff);
        return template;
    }
}
