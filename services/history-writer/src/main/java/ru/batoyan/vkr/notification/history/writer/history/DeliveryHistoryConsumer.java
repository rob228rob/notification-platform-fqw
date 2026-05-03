package ru.batoyan.vkr.notification.history.writer.history;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryHistoryConsumer {

    private final DeliveryStatusEventParser eventParser;
    private final DeliveryHistoryStore repository;

    @KafkaListener(
            topics = {
                    "${outbox.relay.topics.mail-delivery-statuses}",
                    "${outbox.relay.topics.sms-delivery-statuses}"
            },
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(ConsumerRecord<String, String> record) {
        var event = eventParser.parse(record.value())
                .withKafkaMetadata(record.topic(), record.partition(), record.offset());
        var stored = repository.save(event);
        log.info("Delivery history consumed outboxId={}, eventType={}, aggregateId={}, topic={}, partition={}, offset={}, statusStored={}",
                event.outboxId(), event.eventType(), event.aggregateId(), event.kafkaTopic(), event.kafkaPartition(), event.kafkaOffset(), stored);
    }
}
