package ru.batoyan.vkr.notification.history.writer.history;

import ru.notification.history.proto.v1.DeliveryStatusKafkaEvent;
import ru.notification.history.proto.v1.RecipientDeliverySummary;

import java.util.List;
import java.util.Map;

public interface DeliveryHistoryStore {

    boolean save(DeliveryStatusEvent event);

    long countByClientId(String clientId);

    List<DeliveryStatusKafkaEvent> listByClientId(String clientId, int page, int size);

    RecipientDeliverySummary getRecipientSummary(String recipientId, int lookbackHours);

    Map<String, RecipientDeliverySummary> getRecipientSummaries(List<String> recipientIds, int lookbackHours);
}
