package ru.batoyan.vkr.notification.cancellation.repository;

import java.time.Instant;
import java.util.Optional;
import ru.batoyan.vkr.notification.cancellation.model.CancellationRecord;

public interface CancellationRepository {

    CancellationSaveResult save(CancellationRecord record);

    Optional<CancellationLookupResult> findByDispatchId(String dispatchId);

    record CancellationSaveResult(CancellationRecord record, boolean alreadyCancelled, Instant expiresAt) {
    }

    record CancellationLookupResult(CancellationRecord record, Instant expiresAt) {
    }
}
