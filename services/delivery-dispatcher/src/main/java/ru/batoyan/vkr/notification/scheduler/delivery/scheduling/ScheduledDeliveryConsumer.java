package ru.batoyan.vkr.notification.scheduler.delivery.scheduling;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import ru.batoyan.vkr.notification.scheduler.delivery.dispatching.DeliveryDispatcherService;

import java.time.OffsetDateTime;
import java.util.Map;

@Component
public class ScheduledDeliveryConsumer {

    private static final Logger LOG = LogManager.getLogger();

    private final KafkaEnvelopeParser envelopeParser;
    private final ScheduledDeliveryRepository repository;
    private final DeliveryDispatcherService deliveryDispatcherService;

    public ScheduledDeliveryConsumer(
            KafkaEnvelopeParser envelopeParser,
            ScheduledDeliveryRepository repository,
            DeliveryDispatcherService deliveryDispatcherService
    ) {
        this.envelopeParser = envelopeParser;
        this.repository = repository;
        this.deliveryDispatcherService = deliveryDispatcherService;
    }

    @KafkaListener(
            topics = "${dispatcher.delivery.dispatch-topic}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(@Payload String rawMessage, @Headers Map<String, Object> kafkaHeaders) {
        var envelope = envelopeParser.parse(rawMessage);
        var plannedSendAt = ScheduledDeliveryRepository.resolvePlannedSendAt(envelope.payload());
        if (plannedSendAt.isAfter(OffsetDateTime.now())) {
            var stored = repository.save(envelope);
            LOG.info("Dispatch request scheduled outboxId={}, aggregateId={}, eventType={}, stored={}, kafkaHeaders={}",
                    envelope.outboxId(), envelope.aggregateId(), envelope.eventType(), stored, kafkaHeaders);
            return;
        }
        deliveryDispatcherService.routeDispatch(envelope);
        LOG.info("Dispatch request routed immediately aggregateId={}, eventType={}, kafkaHeaders={}",
                envelope.aggregateId(), envelope.eventType(), kafkaHeaders);
    }
}
