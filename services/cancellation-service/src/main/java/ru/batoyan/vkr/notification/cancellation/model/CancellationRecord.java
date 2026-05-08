package ru.batoyan.vkr.notification.cancellation.model;

import java.time.Instant;

public record CancellationRecord(
        String dispatchId,
        String eventId,
        String clientId,
        String reason,
        String requestedBy,
        Instant cancelledAt
) {
}
