package ru.batoyan.vkr.notification.cancellation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    @ParameterizedTest
    @CsvSource({
            "dispatch-1,event-1,client-1,client requested,operator-a",
            "dispatch-2,event-2,client-1,duplicate request,operator-b",
            "dispatch-3,event-3,client-2,timeout,system",
            "dispatch-4,event-4,client-2,manual stop,operator-c",
            "dispatch-5,event-5,client-3,recipient blocked,profile-consent",
            "dispatch-6,event-6,client-3,empty audience,facade",
            "dispatch-7,event-7,client-4,reroute cancelled,dispatcher",
            "dispatch-8,event-8,client-4,tenant disabled,operator-d",
            "dispatch-9,event-9,client-5,test cancellation,tester",
            "dispatch-10,event-10,client-5,scheduled cancellation,scheduler"
    })
    void shouldPreserveCancellationRecordFields(
            String dispatchId,
            String eventId,
            String clientId,
            String reason,
            String requestedBy
    ) {
        var service = new CancellationService(new InMemoryCancellationRepository());

        var result = service.cancelDispatch(dispatchId, eventId, clientId, reason, requestedBy);

        assertThat(result.record().dispatchId()).isEqualTo(dispatchId);
        assertThat(result.record().eventId()).isEqualTo(eventId);
        assertThat(result.record().clientId()).isEqualTo(clientId);
        assertThat(result.record().reason()).isEqualTo(reason);
        assertThat(result.record().requestedBy()).isEqualTo(requestedBy);
    }

    @ParameterizedTest
    @CsvSource({
            "dispatch-1,,client-1,reason,operator",
            "dispatch-2,event-2,,reason,operator",
            "dispatch-3,event-3,client-3,,operator",
            "dispatch-4,event-4,client-4,reason,",
            "dispatch-5,,,reason,",
            "dispatch-6,event-6,,,operator",
            "dispatch-7,,client-7,,",
            "dispatch-8,,,,",
            "dispatch-9,event-9,client-9,manual,",
            "dispatch-10,,client-10,,system"
    })
    void shouldNormalizeNullableOptionalCancellationFields(
            String dispatchId,
            String eventId,
            String clientId,
            String reason,
            String requestedBy
    ) {
        var service = new CancellationService(new InMemoryCancellationRepository());

        var result = service.cancelDispatch(dispatchId, eventId, clientId, reason, requestedBy);

        assertThat(result.record().eventId()).isEqualTo(eventId == null ? "" : eventId);
        assertThat(result.record().clientId()).isEqualTo(clientId == null ? "" : clientId);
        assertThat(result.record().reason()).isEqualTo(reason == null ? "" : reason);
        assertThat(result.record().requestedBy()).isEqualTo(requestedBy == null ? "" : requestedBy);
    }

    @ParameterizedTest
    @CsvSource({
            "dispatch-a,event-a,client-a",
            "dispatch-b,event-b,client-b",
            "dispatch-c,event-c,client-c",
            "dispatch-d,event-d,client-d",
            "dispatch-e,event-e,client-e",
            "dispatch-f,event-f,client-f",
            "dispatch-g,event-g,client-g",
            "dispatch-h,event-h,client-h",
            "dispatch-i,event-i,client-i",
            "dispatch-j,event-j,client-j"
    })
    void shouldFindCancellationAndDisallowDeliveryForStoredDispatches(String dispatchId, String eventId, String clientId) {
        var service = new CancellationService(new InMemoryCancellationRepository());

        service.cancelDispatch(dispatchId, eventId, clientId, "reason", "operator");

        assertThat(service.findCancellation(dispatchId)).isPresent();
        assertThat(service.isDeliveryAllowed(dispatchId)).isFalse();
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
