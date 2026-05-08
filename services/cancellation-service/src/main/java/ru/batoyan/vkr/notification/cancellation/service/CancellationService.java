package ru.batoyan.vkr.notification.cancellation.service;

import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.batoyan.vkr.notification.cancellation.model.CancellationRecord;
import ru.batoyan.vkr.notification.cancellation.repository.CancellationRepository;

@Service
@RequiredArgsConstructor
public class CancellationService {

    private final CancellationRepository repository;

    public CancellationRepository.CancellationSaveResult cancelDispatch(
            String dispatchId,
            String eventId,
            String clientId,
            String reason,
            String requestedBy
    ) {
        var record = new CancellationRecord(
                dispatchId,
                blankToEmpty(eventId),
                blankToEmpty(clientId),
                blankToEmpty(reason),
                blankToEmpty(requestedBy),
                Instant.now()
        );
        return repository.save(record);
    }

    public Optional<CancellationRepository.CancellationLookupResult> findCancellation(String dispatchId) {
        return repository.findByDispatchId(dispatchId);
    }

    public boolean isDeliveryAllowed(String dispatchId) {
        return repository.findByDispatchId(dispatchId).isEmpty();
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }
}
