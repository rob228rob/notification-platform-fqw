package ru.batoyan.vkr.services.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class KafkaConsumer {

    @KafkaListener(groupId = "group-id", topics = "test")
    public void consume(@Payload String message,
                        @Headers Map<String, Object> headers) {
        log.info("Consumed message: {}", message);
        log.info("Headers: {}", headers);
    }
}
