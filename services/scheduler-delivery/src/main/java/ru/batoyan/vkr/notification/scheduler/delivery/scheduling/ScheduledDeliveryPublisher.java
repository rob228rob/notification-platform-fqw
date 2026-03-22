package ru.batoyan.vkr.notification.scheduler.delivery.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.batoyan.vkr.notification.scheduler.delivery.config.SchedulerDeliveryProperties;

import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class ScheduledDeliveryPublisher {

    private static final Logger LOG = LogManager.getLogger();

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SchedulerDeliveryEventSerializer eventSerializer;
    private final ScheduledDeliveryRepository repository;
    private final SchedulerDeliveryProperties properties;

    public ScheduledDeliveryPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            ScheduledDeliveryRepository repository,
            SchedulerDeliveryProperties properties
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.eventSerializer = new SchedulerDeliveryEventSerializer(objectMapper);
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${scheduler.delivery.poll-fixed-delay}")
    public void tick() {
        try {
            var published = publishDueBatch();
            if (published > 0) {
                LOG.info("Scheduled delivery published {} message(s) to topic={}",
                        published, properties.getProducerTopic());
            }
        } catch (Exception ex) {
            LOG.warn("Scheduled delivery tick failed: {}", ex.getMessage(), ex);
        }
    }

    public int publishDueBatch() {
        var tasks = repository.lockDueTasks(properties.getBatchSize());
        if (tasks.isEmpty()) {
            LOG.info("Scheduled delivery poll found no due tasks (batchSize={})", properties.getBatchSize());
            return 0;
        }

        var published = 0;
        for (var task : tasks) {
            try {
                var payload = eventSerializer.serialize(new SchedulerDeliveryEvent(
                        task.taskId(),
                        task.aggregateType(),
                        task.aggregateId(),
                        task.eventType(),
                        task.payload(),
                        task.headers(),
                        task.sourceCreatedAt() == null ? null : task.sourceCreatedAt().toString()
                ));
                kafkaTemplate.send(properties.getProducerTopic(), task.aggregateId(), payload).join();
                repository.markPublished(task.taskId());
                published++;
                LOG.debug("Scheduled task published taskId={}, aggregateId={}, eventType={}",
                        task.taskId(), task.aggregateId(), task.eventType());
            } catch (Exception ex) {
                var retryAt = OffsetDateTime.now().plus(properties.getRetryBackoff());
                repository.markRetry(task.taskId(), ex.getMessage(), retryAt);
                LOG.warn("Scheduled publish failed taskId={}, aggregateId={}, retryAt={}, err={}",
                        task.taskId(), task.aggregateId(), retryAt, ex.getMessage(), ex);
            }
        }
        return published;
    }

    private record SchedulerDeliveryEvent(
            long outboxId,
            String aggregateType,
            String aggregateId,
            String eventType,
            Map<String, Object> payload,
            Map<String, Object> headers,
            String createdAt
    ) {
    }

    private static final class SchedulerDeliveryEventSerializer {
        private final ObjectMapper objectMapper;

        private SchedulerDeliveryEventSerializer(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        private String serialize(SchedulerDeliveryEvent event) {
            try {
                return objectMapper.writeValueAsString(event);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to serialize scheduled delivery event", ex);
            }
        }
    }
}
