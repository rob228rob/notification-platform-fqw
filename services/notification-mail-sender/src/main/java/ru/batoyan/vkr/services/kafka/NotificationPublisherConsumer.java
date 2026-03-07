package ru.batoyan.vkr.services.kafka;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@NullMarked
@Component
public class NotificationPublisherConsumer {

    private static final Logger LOG = LogManager.getLogger(NotificationPublisherConsumer.class);

  //  private final DeliveryOrchestrator orchestrator;

//    public NotificationPublisherConsumer(DeliveryOrchestrator orchestrator) {
//       // this.orchestrator = orchestrator;
//    }

    @KafkaListener(
            topics = {"${outbox.relay.topics.events}", "${outbox.relay.topics.dispatches}"},
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(Map<String, Object> msg) {

        var outboxId = asLong(msg.get("outboxId"));
        var aggregateType = asString(msg.get("aggregateType"));
        var aggregateId = asString(msg.get("aggregateId"));
        var eventType = asString(msg.get("eventType"));

        LOG.info("[PUBLISHER] received outboxId={}, aggregateType={}, aggregateId={}, eventType={}",
                outboxId, aggregateType, aggregateId, eventType);

        //orchestrator.handle(msg);
    }

    private static String asString(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static long asLong(@Nullable Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(v));
    }
}