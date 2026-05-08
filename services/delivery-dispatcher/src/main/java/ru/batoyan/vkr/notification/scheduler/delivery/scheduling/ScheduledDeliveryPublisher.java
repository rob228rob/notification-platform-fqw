package ru.batoyan.vkr.notification.scheduler.delivery.scheduling;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.batoyan.vkr.notification.scheduler.delivery.config.SchedulerDeliveryProperties;
import ru.batoyan.vkr.notification.scheduler.delivery.dispatching.DeliveryDispatcherService;

import java.time.OffsetDateTime;

@Service
public class ScheduledDeliveryPublisher {

    private static final Logger LOG = LogManager.getLogger();

    private final ScheduledDeliveryRepository repository;
    private final SchedulerDeliveryProperties properties;
    private final DeliveryDispatcherService deliveryDispatcherService;

    public ScheduledDeliveryPublisher(
            ScheduledDeliveryRepository repository,
            SchedulerDeliveryProperties properties,
            DeliveryDispatcherService deliveryDispatcherService
    ) {
        this.repository = repository;
        this.properties = properties;
        this.deliveryDispatcherService = deliveryDispatcherService;
    }

    @Scheduled(fixedDelayString = "${dispatcher.delivery.poll-fixed-delay}")
    public void tick() {
        try {
            var published = publishDueBatch();
            if (published > 0) {
                LOG.info("Scheduled delivery routed {} due task(s)", published);
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
                deliveryDispatcherService.routeDispatch(new KafkaEnvelope(
                        task.taskId(),
                        task.aggregateType(),
                        task.aggregateId(),
                        task.eventType(),
                        task.payload(),
                        task.headers(),
                        task.sourceCreatedAt() == null ? OffsetDateTime.now() : task.sourceCreatedAt()
                ));
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
}
