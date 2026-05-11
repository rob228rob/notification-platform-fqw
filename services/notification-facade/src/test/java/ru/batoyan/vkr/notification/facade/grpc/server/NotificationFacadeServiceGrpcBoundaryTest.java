package ru.batoyan.vkr.notification.facade.grpc.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.FieldMask;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import ru.batoyan.vkr.notification.facade.helpers.ClientIdResolver;
import ru.batoyan.vkr.notification.facade.usecase.NotificationFacadeUseCase;
import ru.notification.common.proto.v1.DeliveryPriority;
import ru.notification.facade.proto.v1.CancelDispatchRequest;
import ru.notification.facade.proto.v1.CancelDispatchResponse;
import ru.notification.facade.proto.v1.CreateEventRequest;
import ru.notification.facade.proto.v1.CreateEventResponse;
import ru.notification.facade.proto.v1.DeliveryStrategy;
import ru.notification.facade.proto.v1.DispatchStatus;
import ru.notification.facade.proto.v1.EventStatus;
import ru.notification.facade.proto.v1.GetEventRequest;
import ru.notification.facade.proto.v1.GetEventResponse;
import ru.notification.facade.proto.v1.ListEventsRequest;
import ru.notification.facade.proto.v1.ListEventsResponse;
import ru.notification.facade.proto.v1.StrategyKind;
import ru.notification.facade.proto.v1.TriggerDispatchRequest;
import ru.notification.facade.proto.v1.TriggerDispatchResponse;
import ru.notification.facade.proto.v1.UpdateEventRequest;
import ru.notification.facade.proto.v1.UpdateEventResponse;

class NotificationFacadeServiceGrpcBoundaryTest {

    @Test
    void createNotificationEventShouldDelegateToUseCase() {
        var fixture = new Fixture();
        var request = validCreateRequest();
        when(fixture.useCase.create(request, "client-1")).thenReturn(CreateEventResponse.newBuilder()
                .setEventId("event-1")
                .setStatus(EventStatus.EVENT_STATUS_DRAFT)
                .build());
        var observer = new TestObserver<CreateEventResponse>();

        fixture.grpc.createNotificationEvent(request, observer);

        assertThat(observer.value.getEventId()).isEqualTo("event-1");
        assertThat(observer.completed).isTrue();
        verify(fixture.useCase).create(request, "client-1");
    }

    @Test
    void createNotificationEventShouldRejectInvalidRequestBeforeUseCase() {
        var observer = new TestObserver<CreateEventResponse>();

        new Fixture().grpc.createNotificationEvent(CreateEventRequest.newBuilder().build(), observer);

        assertThat(status(observer.error)).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void updateNotificationEventShouldRejectInvalidUpdateMask() {
        var observer = new TestObserver<UpdateEventResponse>();

        new Fixture().grpc.updateNotificationEvent(UpdateEventRequest.newBuilder()
                .setEventId("00000000-0000-0000-0000-000000000001")
                .setUpdateMask(FieldMask.newBuilder().addPaths("client_id"))
                .build(), observer);

        assertThat(status(observer.error)).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void getNotificationEventShouldDelegateToUseCase() {
        var fixture = new Fixture();
        var request = GetEventRequest.newBuilder()
                .setEventId("00000000-0000-0000-0000-000000000001")
                .build();
        when(fixture.useCase.getEvent(request, "client-1")).thenReturn(GetEventResponse.newBuilder().build());
        var observer = new TestObserver<GetEventResponse>();

        fixture.grpc.getNotificationEvent(request, observer);

        assertThat(observer.completed).isTrue();
        verify(fixture.useCase).getEvent(request, "client-1");
    }

    @Test
    void listNotificationEventsShouldRejectInvalidPageSize() {
        var observer = new TestObserver<ListEventsResponse>();

        new Fixture().grpc.listNotificationEvents(ListEventsRequest.newBuilder().setPage(0).setSize(0).build(), observer);

        assertThat(status(observer.error)).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void triggerDispatchShouldRequireIdempotencyKey() {
        var observer = new TestObserver<TriggerDispatchResponse>();

        new Fixture().grpc.triggerDispatch(TriggerDispatchRequest.newBuilder()
                .setEventId("00000000-0000-0000-0000-000000000001")
                .build(), observer);

        assertThat(status(observer.error)).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void triggerDispatchShouldDelegateToUseCase() {
        var fixture = new Fixture();
        var request = TriggerDispatchRequest.newBuilder()
                .setEventId("00000000-0000-0000-0000-000000000001")
                .setIdempotencyKey("dispatch-idem")
                .build();
        when(fixture.useCase.triggerDispatch(request, "client-1")).thenReturn(TriggerDispatchResponse.newBuilder()
                .setDispatchId("dispatch-1")
                .setStatus(DispatchStatus.DISPATCH_STATUS_PENDING)
                .build());
        var observer = new TestObserver<TriggerDispatchResponse>();

        fixture.grpc.triggerDispatch(request, observer);

        assertThat(observer.value.getDispatchId()).isEqualTo("dispatch-1");
        verify(fixture.useCase).triggerDispatch(request, "client-1");
    }

    @Test
    void cancelDispatchShouldDelegateToUseCase() {
        var fixture = new Fixture();
        var request = CancelDispatchRequest.newBuilder()
                .setDispatchId("00000000-0000-0000-0000-000000000002")
                .setReason("client requested")
                .build();
        when(fixture.useCase.cancelDispatch(request, "client-1")).thenReturn(CancelDispatchResponse.newBuilder()
                .setDispatchId(request.getDispatchId())
                .setStatus(DispatchStatus.DISPATCH_STATUS_CANCELLED)
                .build());
        var observer = new TestObserver<CancelDispatchResponse>();

        fixture.grpc.cancelDispatch(request, observer);

        assertThat(observer.value.getStatus()).isEqualTo(DispatchStatus.DISPATCH_STATUS_CANCELLED);
        verify(fixture.useCase).cancelDispatch(request, "client-1");
    }

    private static CreateEventRequest validCreateRequest() {
        return CreateEventRequest.newBuilder()
                .setIdempotencyKey("idem-1")
                .setTemplateId("template-1")
                .setTemplateVersion(1)
                .setPriority(DeliveryPriority.DELIVERY_PRIORITY_NORMAL)
                .setStrategy(DeliveryStrategy.newBuilder().setKind(StrategyKind.STRATEGY_KIND_IMMEDIATE).build())
                .build();
    }

    private static final class Fixture {
        private final NotificationFacadeUseCase useCase = mock(NotificationFacadeUseCase.class);
        private final ClientIdResolver clientIdResolver = mock(ClientIdResolver.class);
        private final NotificationFacadeService grpc = new NotificationFacadeService(useCase, clientIdResolver);

        private Fixture() {
            when(clientIdResolver.requireClientId()).thenReturn("client-1");
        }
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
