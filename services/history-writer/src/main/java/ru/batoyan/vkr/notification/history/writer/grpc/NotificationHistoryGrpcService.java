package ru.batoyan.vkr.notification.history.writer.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import ru.batoyan.vkr.notification.history.writer.history.DeliveryHistoryRepository;
import ru.notification.history.proto.v1.ListClientHistoryRequest;
import ru.notification.history.proto.v1.ListClientHistoryResponse;
import ru.notification.history.proto.v1.NotificationHistoryServiceGrpc;

@GrpcService
@RequiredArgsConstructor
public class NotificationHistoryGrpcService extends NotificationHistoryServiceGrpc.NotificationHistoryServiceImplBase {

    private static final int MAX_PAGE_SIZE = 200;

    private final DeliveryHistoryRepository repository;

    @Override
    public void listClientHistory(ListClientHistoryRequest request, StreamObserver<ListClientHistoryResponse> responseObserver) {
        try {
            var clientId = request.getClientId();
            if (clientId == null || clientId.isBlank()) {
                throw Status.INVALID_ARGUMENT.withDescription("client_id must not be blank").asRuntimeException();
            }
            int page = Math.max(request.getPage(), 0);
            int size = request.getSize() <= 0 ? 50 : Math.min(request.getSize(), MAX_PAGE_SIZE);

            var events = repository.listByClientId(clientId, page, size);
            var total = repository.countByClientId(clientId);

            responseObserver.onNext(ListClientHistoryResponse.newBuilder()
                    .addAllEvents(events)
                    .setTotal(total)
                    .setPage(page)
                    .setSize(size)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception ex) {
            responseObserver.onError(ex);
        }
    }
}
