package ru.batoyan.vkr.notification.cancellation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.batoyan.vkr.notification.cancellation.model.CancellationRecord;
import ru.batoyan.vkr.notification.cancellation.repository.CancellationRepository;

class CancellationServiceTest {

    @Test
    void shouldSaveCancellationRecordWithDispatchData() {
        var repository = new InMemoryCancellationRepository();
        var service = new CancellationService(repository);

        var result = service.cancelDispatch("dispatch-1", "event-1", "client-1", "reason", "operator");

        assertThat(result.alreadyCancelled()).isFalse();
        assertThat(result.record().dispatchId()).isEqualTo("dispatch-1");
        assertThat(result.record().eventId()).isEqualTo("event-1");
        assertThat(result.record().clientId()).isEqualTo("client-1");
        assertThat(result.record().reason()).isEqualTo("reason");
        assertThat(result.record().requestedBy()).isEqualTo("operator");
    }

    @Test
    void shouldNormalizeNullableOptionalFieldsToEmptyStrings() {
        var repository = new InMemoryCancellationRepository();
        var service = new CancellationService(repository);

        var result = service.cancelDispatch("dispatch-1", null, null, null, null);

        assertThat(result.record().eventId()).isEmpty();
        assertThat(result.record().clientId()).isEmpty();
        assertThat(result.record().reason()).isEmpty();
        assertThat(result.record().requestedBy()).isEmpty();
    }

    @Test
    void shouldBeIdempotentWhenDispatchAlreadyCancelled() {
        var repository = new InMemoryCancellationRepository();
        var service = new CancellationService(repository);

        service.cancelDispatch("dispatch-1", "event-1", "client-1", "first", "operator");
        var result = service.cancelDispatch("dispatch-1", "event-1", "client-1", "second", "operator");

        assertThat(result.alreadyCancelled()).isTrue();
        assertThat(result.record().reason()).isEqualTo("first");
    }

    @Test
    void shouldFindExistingCancellation() {
        var repository = new InMemoryCancellationRepository();
        var service = new CancellationService(repository);
        service.cancelDispatch("dispatch-1", "event-1", "client-1", "reason", "operator");

        var result = service.findCancellation("dispatch-1");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().record().dispatchId()).isEqualTo("dispatch-1");
    }

    @Test
    void shouldReturnEmptyForUnknownCancellation() {
        var service = new CancellationService(new InMemoryCancellationRepository());

        assertThat(service.findCancellation("missing")).isEmpty();
    }

    @Test
    void shouldDisallowDeliveryWhenCancellationExists() {
        var repository = new InMemoryCancellationRepository();
        var service = new CancellationService(repository);
        service.cancelDispatch("dispatch-1", "event-1", "client-1", "reason", "operator");

        assertThat(service.isDeliveryAllowed("dispatch-1")).isFalse();
    }

    @Test
    void shouldAllowDeliveryWhenCancellationDoesNotExist() {
        var service = new CancellationService(new InMemoryCancellationRepository());

        assertThat(service.isDeliveryAllowed("dispatch-1")).isTrue();
    }

    @Test
    void shouldPropagateRepositoryErrorsAsControlledFailureBoundary() {
        var service = new CancellationService(new FailingCancellationRepository());

        assertThatThrownBy(() -> service.isDeliveryAllowed("dispatch-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis unavailable");
    }

    private static final class InMemoryCancellationRepository implements CancellationRepository {
        private final Map<String, CancellationRecord> records = new LinkedHashMap<>();
        private final Instant expiresAt = Instant.parse("2026-05-12T12:30:00Z");

        @Override
        public CancellationSaveResult save(CancellationRecord record) {
            var existing = records.putIfAbsent(record.dispatchId(), record);
            var stored = existing == null ? record : existing;
            return new CancellationSaveResult(stored, existing != null, expiresAt);
        }

        @Override
        public Optional<CancellationLookupResult> findByDispatchId(String dispatchId) {
            return Optional.ofNullable(records.get(dispatchId))
                    .map(record -> new CancellationLookupResult(record, expiresAt));
        }
    }

    private static final class FailingCancellationRepository implements CancellationRepository {
        @Override
        public CancellationSaveResult save(CancellationRecord record) {
            throw new IllegalStateException("redis unavailable");
        }

        @Override
        public Optional<CancellationLookupResult> findByDispatchId(String dispatchId) {
            throw new IllegalStateException("redis unavailable");
        }
    }
}
