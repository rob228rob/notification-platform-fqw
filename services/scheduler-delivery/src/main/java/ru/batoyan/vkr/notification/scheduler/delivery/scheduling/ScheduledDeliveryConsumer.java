package ru.batoyan.vkr.notification.scheduler.delivery.scheduling;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ScheduledDeliveryConsumer {

    private static final Logger LOG = LogManager.getLogger();

    private final KafkaEnvelopeParser envelopeParser;
    private final ScheduledDeliveryRepository repository;

    public ScheduledDeliveryConsumer(KafkaEnvelopeParser envelopeParser, ScheduledDeliveryRepository repository) {
        this.envelopeParser = envelopeParser;
        this.repository = repository;
    }

    @KafkaListener(
            topics = "${scheduler.delivery.consumer-topic}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(@Payload String rawMessage, @Headers Map<String, Object> kafkaHeaders) {
        var envelope = envelopeParser.parse(rawMessage);
        var stored = repository.save(envelope);
        LOG.info("Scheduled message consumed outboxId={}, aggregateId={}, eventType={}, stored={}, kafkaHeaders={}",
                envelope.outboxId(),
                envelope.aggregateId(),
                envelope.eventType(),
                stored,
                kafkaHeaders);
    }
}
