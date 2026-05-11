package ru.batoyan.vkr.notification.history.writer.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.batoyan.vkr.notification.history.writer.history.DeliveryHistoryStore;
import ru.notification.history.proto.v1.BatchGetRecipientDeliverySummariesRequest;
import ru.notification.history.proto.v1.BatchGetRecipientDeliverySummariesResponse;
import ru.notification.history.proto.v1.GetRecipientDeliverySummaryRequest;
import ru.notification.history.proto.v1.GetRecipientDeliverySummaryResponse;
import ru.notification.history.proto.v1.ListClientHistoryRequest;
import ru.notification.history.proto.v1.ListClientHistoryResponse;
import ru.notification.history.proto.v1.RecipientDeliverySummary;

class NotificationHistoryGrpcServiceTest {

    @Test
    void listClientHistoryShouldReturnEventsAndTotal() {
        var store = mock(DeliveryHistoryStore.class);
        when(store.countByClientId("client-1")).thenReturn(10L);
        when(store.listByClientId("client-1", 0, 50)).thenReturn(List.of());
        var observer = new TestObserver<ListClientHistoryResponse>();

        new NotificationHistoryGrpcService(store).listClientHistory(ListClientHistoryRequest.newBuilder()
                .setClientId("client-1")
                .build(), observer);

        assertThat(observer.value.getTotal()).isEqualTo(10L);
        assertThat(observer.value.getSize()).isEqualTo(50);
        verify(store).listByClientId("client-1", 0, 50);
    }

    @Test
    void listClientHistoryShouldClampPageAndSize() {
        var store = mock(DeliveryHistoryStore.class);
        when(store.countByClientId("client-1")).thenReturn(0L);
        when(store.listByClientId("client-1", 0, 200)).thenReturn(List.of());
        var observer = new TestObserver<ListClientHistoryResponse>();

        new NotificationHistoryGrpcService(store).listClientHistory(ListClientHistoryRequest.newBuilder()
                .setClientId("client-1")
                .setPage(-1)
                .setSize(1000)
                .build(), observer);

        assertThat(observer.value.getPage()).isZero();
        assertThat(observer.value.getSize()).isEqualTo(200);
    }

    @Test
    void listClientHistoryShouldRejectBlankClientId() {
        var observer = new TestObserver<ListClientHistoryResponse>();

        new NotificationHistoryGrpcService(mock(DeliveryHistoryStore.class))
                .listClientHistory(ListClientHistoryRequest.newBuilder().build(), observer);

        assertThat(status(observer.error)).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void getRecipientDeliverySummaryShouldUseDefaultLookback() {
        var store = mock(DeliveryHistoryStore.class);
        var summary = RecipientDeliverySummary.newBuilder().setRecipientId("recipient-1").build();
        when(store.getRecipientSummary("recipient-1", 24)).thenReturn(summary);
        var observer = new TestObserver<GetRecipientDeliverySummaryResponse>();

        new NotificationHistoryGrpcService(store).getRecipientDeliverySummary(GetRecipientDeliverySummaryRequest.newBuilder()
                .setRecipientId("recipient-1")
                .build(), observer);

        assertThat(observer.value.getSummary().getRecipientId()).isEqualTo("recipient-1");
        verify(store).getRecipientSummary("recipient-1", 24);
    }

    @Test
    void getRecipientDeliverySummaryShouldRejectBlankRecipientId() {
        var observer = new TestObserver<GetRecipientDeliverySummaryResponse>();

        new NotificationHistoryGrpcService(mock(DeliveryHistoryStore.class))
                .getRecipientDeliverySummary(GetRecipientDeliverySummaryRequest.newBuilder().build(), observer);

        assertThat(status(observer.error)).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void batchGetRecipientDeliverySummariesShouldReturnSummaries() {
        var store = mock(DeliveryHistoryStore.class);
        var summary = RecipientDeliverySummary.newBuilder().setRecipientId("recipient-1").build();
        when(store.getRecipientSummaries(List.of("recipient-1"), 24)).thenReturn(Map.of("recipient-1", summary));
        var observer = new TestObserver<BatchGetRecipientDeliverySummariesResponse>();

        new NotificationHistoryGrpcService(store).batchGetRecipientDeliverySummaries(BatchGetRecipientDeliverySummariesRequest.newBuilder()
                .addRecipientId("recipient-1")
                .build(), observer);

        assertThat(observer.value.getSummariesCount()).isEqualTo(1);
        verify(store).getRecipientSummaries(List.of("recipient-1"), 24);
    }

    @Test
    void batchGetRecipientDeliverySummariesShouldRejectEmptyBatch() {
        var observer = new TestObserver<BatchGetRecipientDeliverySummariesResponse>();

        new NotificationHistoryGrpcService(mock(DeliveryHistoryStore.class))
                .batchGetRecipientDeliverySummaries(BatchGetRecipientDeliverySummariesRequest.newBuilder().build(), observer);

        assertThat(status(observer.error)).isEqualTo(Status.Code.INVALID_ARGUMENT);
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
