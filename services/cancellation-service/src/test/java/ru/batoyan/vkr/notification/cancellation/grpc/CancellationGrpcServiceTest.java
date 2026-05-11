package ru.batoyan.vkr.notification.cancellation.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.batoyan.vkr.notification.cancellation.model.CancellationRecord;
import ru.batoyan.vkr.notification.cancellation.repository.CancellationRepository;
import ru.batoyan.vkr.notification.cancellation.service.CancellationService;
import ru.notification.cancellation.proto.v1.CancelDispatchRequest;
import ru.notification.cancellation.proto.v1.CancelDispatchResponse;
import ru.notification.cancellation.proto.v1.CheckDeliveryAllowedRequest;
import ru.notification.cancellation.proto.v1.CheckDeliveryAllowedResponse;

class CancellationGrpcServiceTest {

    @Test
    void cancelDispatchShouldReturnCancellationResponse() {
        var service = mock(CancellationService.class);
        var record = record();
        when(service.cancelDispatch("dispatch-1", "event-1", "client-1", "reason", "operator"))
                .thenReturn(new CancellationRepository.CancellationSaveResult(record, false, Instant.parse("2026-05-12T10:30:00Z")));
        var grpc = new CancellationGrpcService(service);
        var observer = new TestObserver<CancelDispatchResponse>();

        grpc.cancelDispatch(CancelDispatchRequest.newBuilder()
                .setDispatchId("dispatch-1")
                .setEventId("event-1")
                .setClientId("client-1")
                .setReason("reason")
                .setRequestedBy("operator")
                .build(), observer);

        assertThat(observer.completed).isTrue();
        assertThat(observer.value.getDispatchId()).isEqualTo("dispatch-1");
        assertThat(observer.value.getCancelled()).isTrue();
        assertThat(observer.value.getAlreadyCancelled()).isFalse();
        verify(service).cancelDispatch("dispatch-1", "event-1", "client-1", "reason", "operator");
    }

    @Test
    void cancelDispatchShouldRejectBlankDispatchId() {
        var grpc = new CancellationGrpcService(mock(CancellationService.class));
        var observer = new TestObserver<CancelDispatchResponse>();

        grpc.cancelDispatch(CancelDispatchRequest.newBuilder().build(), observer);

        assertThat(status(observer.error)).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void checkDeliveryAllowedShouldReturnAllowedWhenCancellationMissing() {
        var service = mock(CancellationService.class);
        when(service.findCancellation("dispatch-1")).thenReturn(Optional.empty());
        var observer = new TestObserver<CheckDeliveryAllowedResponse>();

        new CancellationGrpcService(service).checkDeliveryAllowed(CheckDeliveryAllowedRequest.newBuilder()
                .setDispatchId("dispatch-1")
                .build(), observer);

        assertThat(observer.value.getAllowed()).isTrue();
        assertThat(observer.value.getCancelled()).isFalse();
        assertThat(observer.completed).isTrue();
    }

    @Test
    void checkDeliveryAllowedShouldReturnCancelledWhenCancellationExists() {
        var service = mock(CancellationService.class);
        when(service.findCancellation("dispatch-1")).thenReturn(Optional.of(
                new CancellationRepository.CancellationLookupResult(record(), Instant.parse("2026-05-12T10:30:00Z"))
        ));
        var observer = new TestObserver<CheckDeliveryAllowedResponse>();

        new CancellationGrpcService(service).checkDeliveryAllowed(CheckDeliveryAllowedRequest.newBuilder()
                .setDispatchId("dispatch-1")
                .build(), observer);

        assertThat(observer.value.getAllowed()).isFalse();
        assertThat(observer.value.getCancelled()).isTrue();
        assertThat(observer.value.getReason()).isEqualTo("reason");
    }

    @Test
    void checkDeliveryAllowedShouldRejectBlankDispatchId() {
        var grpc = new CancellationGrpcService(mock(CancellationService.class));
        var observer = new TestObserver<CheckDeliveryAllowedResponse>();

        grpc.checkDeliveryAllowed(CheckDeliveryAllowedRequest.newBuilder().build(), observer);

        assertThat(status(observer.error)).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    private static CancellationRecord record() {
        return new CancellationRecord(
                "dispatch-1",
                "event-1",
                "client-1",
                "reason",
                "operator",
                Instant.parse("2026-05-12T10:00:00Z")
        );
    }

    private static Status.Code status(Throwable error) {
        return ((StatusRuntimeException) error).getStatus().getCode();
    }

    private static final class TestObserver<T> implements StreamObserver<T> {
        private T value;
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(T value) {
            this.value = value;
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }
}
