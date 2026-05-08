package ru.batoyan.vkr.notification.cancellation.grpc;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import ru.batoyan.vkr.notification.cancellation.service.CancellationService;
import ru.notification.cancellation.proto.v1.CancelDispatchRequest;
import ru.notification.cancellation.proto.v1.CancelDispatchResponse;
import ru.notification.cancellation.proto.v1.CancellationServiceGrpc;
import ru.notification.cancellation.proto.v1.CheckDeliveryAllowedRequest;
import ru.notification.cancellation.proto.v1.CheckDeliveryAllowedResponse;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class CancellationGrpcService extends CancellationServiceGrpc.CancellationServiceImplBase {

    private final CancellationService cancellationService;

    @Override
    public void cancelDispatch(CancelDispatchRequest request, StreamObserver<CancelDispatchResponse> responseObserver) {
        if (request.getDispatchId().isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("dispatch_id must not be blank").asRuntimeException());
            return;
        }
        var result = cancellationService.cancelDispatch(
                request.getDispatchId(),
                request.getEventId(),
                request.getClientId(),
                request.getReason(),
                request.getRequestedBy()
        );
        var response = CancelDispatchResponse.newBuilder()
                .setDispatchId(result.record().dispatchId())
                .setCancelled(true)
                .setAlreadyCancelled(result.alreadyCancelled())
                .setCancelledAt(timestamp(result.record().cancelledAt()))
                .setExpiresAt(timestamp(result.expiresAt()))
                .build();
        log.info("Dispatch cancellation recorded dispatchId={}, alreadyCancelled={}, expiresAt={}",
                request.getDispatchId(), result.alreadyCancelled(), result.expiresAt());
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void checkDeliveryAllowed(
            CheckDeliveryAllowedRequest request,
            StreamObserver<CheckDeliveryAllowedResponse> responseObserver
    ) {
        if (request.getDispatchId().isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("dispatch_id must not be blank").asRuntimeException());
            return;
        }
        var cancellation = cancellationService.findCancellation(request.getDispatchId());
        var response = CheckDeliveryAllowedResponse.newBuilder()
                .setDispatchId(request.getDispatchId())
                .setAllowed(cancellation.isEmpty())
                .setCancelled(cancellation.isPresent())
                .setCheckedAt(timestamp(Instant.now()));
        cancellation.ifPresent(result -> {
            response.setReason(result.record().reason());
            response.setExpiresAt(timestamp(result.expiresAt()));
        });
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
