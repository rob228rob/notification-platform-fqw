package ru.batoyan.vkr.notification.history.writer.history;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryHistoryConsumer {

    private final DeliveryStatusEventParser eventParser;
    private final DeliveryHistoryRepository repository;

    @KafkaListener(
            topics = "${outbox.relay.topics.mail-delivery-statuses}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(String rawMessage) {
        var event = eventParser.parse(rawMessage);
        var stored = repository.save(event);
        log.info("Delivery history consumed outboxId={}, aggregateId={}, statusStored={}",
                event.outboxId(), event.aggregateId(), stored);
    }
}
