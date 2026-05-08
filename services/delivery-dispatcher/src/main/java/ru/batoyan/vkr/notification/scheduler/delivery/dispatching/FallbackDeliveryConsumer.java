package ru.batoyan.vkr.notification.scheduler.delivery.dispatching;

import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import ru.batoyan.vkr.notification.scheduler.delivery.config.SchedulerDeliveryProperties;
import ru.batoyan.vkr.notification.scheduler.delivery.scheduling.KafkaEnvelopeParser;

@Component
public class FallbackDeliveryConsumer {

    private static final Logger LOG = LogManager.getLogger();

    private final KafkaEnvelopeParser envelopeParser;
    private final DeliveryDispatcherService deliveryDispatcherService;

    public FallbackDeliveryConsumer(
            KafkaEnvelopeParser envelopeParser,
            DeliveryDispatcherService deliveryDispatcherService,
            SchedulerDeliveryProperties properties
    ) {
        this.envelopeParser = envelopeParser;
        this.deliveryDispatcherService = deliveryDispatcherService;
    }

    @KafkaListener(
            topics = "${dispatcher.delivery.fallback-topic}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(@Payload String rawMessage, @Headers Map<String, Object> headers) {
        var envelope = envelopeParser.parse(rawMessage);
        deliveryDispatcherService.handleFallback(envelope);
        LOG.info("Fallback event processed aggregateId={}, headers={}", envelope.aggregateId(), headers);
    }
}
