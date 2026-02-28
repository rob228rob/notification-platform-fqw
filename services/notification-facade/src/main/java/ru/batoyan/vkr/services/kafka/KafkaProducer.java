//package ru.batoyan.vkr.services.kafka;
//
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.kafka.core.KafkaTemplate;
//import org.springframework.stereotype.Service;
//
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class KafkaProducer {
//
//    private final KafkaTemplate<String, String> kafkaTemplate;
//
//    public void sendMessage(String topic, String message) {
//        kafkaTemplate.send(topic, message).whenComplete((result, ex) -> {
//            if (ex == null) {
//                log.info("Successfully sent message=[{}] to topic=[{}]", message, topic);
//            } else {
//                log.error("Failed to send message=[{}] to topic=[{}]", message, topic, ex);
//            }
//        });
//    }
//}
