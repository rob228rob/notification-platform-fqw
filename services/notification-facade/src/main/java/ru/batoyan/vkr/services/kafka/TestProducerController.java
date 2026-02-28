//package ru.batoyan.vkr.services.kafka;
//
//import lombok.RequiredArgsConstructor;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RequestParam;
//import org.springframework.web.bind.annotation.RestController;
//
//@RestController
//@RequestMapping("/kafka")
//@RequiredArgsConstructor
//public class TestProducerController {
//
//    private final KafkaProducer kafkaProducer;
//
//    @PostMapping("/send")
//    public ResponseEntity<Void> send(@RequestParam String msg) {
//        kafkaProducer.sendMessage("test", msg);
//        return ResponseEntity.ok().build();
//    }
//}
