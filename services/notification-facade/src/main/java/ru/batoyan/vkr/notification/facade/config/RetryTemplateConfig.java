package ru.batoyan.vkr.notification.facade.config;

import org.jspecify.annotations.NullMarked;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import ru.batoyan.vkr.notification.facade.outbox.OutboxRelayProperties;

@NullMarked
@Configuration
public class RetryTemplateConfig {

    @Bean
    public RetryTemplate outboxRetryTemplate(OutboxRelayProperties props) {
        var retry = props.getProducerRetry();

        var template = new RetryTemplate();
        template.setRetryPolicy(new SimpleRetryPolicy(retry.maxAttempts()));

        var backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(retry.initialBackoff().toMillis());
        backoff.setMultiplier(retry.multiplier());
        backoff.setMaxInterval(retry.maxBackoff().toMillis());
        template.setBackOffPolicy(backoff);

        return template;
    }
}