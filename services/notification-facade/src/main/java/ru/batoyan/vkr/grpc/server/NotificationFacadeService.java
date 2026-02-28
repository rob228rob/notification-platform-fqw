package ru.batoyan.vkr.grpc.server;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ru.batoyan.vkr.helpers.ClientIdResolver;
import ru.batoyan.vkr.usecase.NotificationFacadeUseCase;
import ru.notification.facade.proto.v1.*;

import static ru.batoyan.vkr.helpers.Validations.*;

/**
 * @author batoyan.rl
 * @since 23.02.2026
 */
@GrpcService
public class NotificationFacadeService extends NotificationFacadeGrpc.NotificationFacadeImplBase {
    private final NotificationFacadeUseCase useCase;
    private final ClientIdResolver clientIdResolver;

    private static final Logger LOG = LogManager.getLogger(NotificationFacadeService.class);

    public NotificationFacadeService(NotificationFacadeUseCase useCase, ClientIdResolver clientIdResolver) {
        this.useCase = useCase;
        this.clientIdResolver = clientIdResolver;
    }

    // --- Events Management ---

    @Override
    public void createNotificationEvent(CreateEventRequest request, StreamObserver<CreateEventResponse> responseObserver) {
        execute("createNotificationEvent", () -> {
            var clientId = clientIdResolver.requireClientId();
            LOG.info("[CREATE_EVENT] Request from client {}: {}", clientId, request);

            validateCreate(request);
            var resp = useCase.create(request, clientId);

            LOG.info("[CREATE_EVENT] Success. New EventId: {}", resp.getEventId());
            return resp;
        }, responseObserver);
    }

    @Override
    public void updateNotificationEvent(UpdateEventRequest request, StreamObserver<UpdateEventResponse> responseObserver) {
        execute("updateNotificationEvent", () -> {
            var clientId = clientIdResolver.requireClientId();
            LOG.info("[UPDATE_EVENT] Request from client {} for EventId {}: {}", clientId, request.getEventId(), request);

            validateUpdate(request);
            var resp = useCase.update(request, clientId);

            LOG.info("[UPDATE_EVENT] Success for EventId: {}", request.getEventId());
            return resp;
        }, responseObserver);
    }

    @Override
    public void cancelNotificationEvent(CancelEventRequest request, StreamObserver<CancelEventResponse> responseObserver) {
        execute("cancelNotificationEvent", () -> {
            var clientId = clientIdResolver.requireClientId();
            LOG.info("[CANCEL_EVENT] Request from client {} for EventId: {}", clientId, request.getEventId());

            requireUuid(request.getEventId(), "event_id");
            var resp = useCase.cancel(request, clientId);

            LOG.info("[CANCEL_EVENT] Event {} cancelled successfully", request.getEventId());
            return resp;
        }, responseObserver);
    }

    @Override
    public void getNotificationEvent(GetEventRequest request, StreamObserver<GetEventResponse> responseObserver) {
        execute("getNotificationEvent", () -> {
            var clientId = clientIdResolver.requireClientId();
            LOG.debug("[GET_EVENT] Request from client {} for EventId: {}", clientId, request.getEventId());

            requireUuid(request.getEventId(), "event_id");
            return useCase.getEvent(request, clientId);
        }, responseObserver);
    }

    @Override
    public void listNotificationEvents(ListEventsRequest request, StreamObserver<ListEventsResponse> responseObserver) {
        execute("listNotificationEvents", () -> {
            var clientId = clientIdResolver.requireClientId();
            LOG.info("[LIST_EVENTS] Client: {}, Page: {}, Size: {}", clientId, request.getPage(), request.getSize());

            validateList(request.getPage(), request.getSize());
            var resp = useCase.listEvents(request, clientId);

            LOG.debug("[LIST_EVENTS] Returned {} events", resp.getEventsCount());
            return resp;
        }, responseObserver);
    }

    // --- Audience Management ---

    @Override
    public void setAudience(SetAudienceRequest request, StreamObserver<SetAudienceResponse> responseObserver) {
        execute("setAudience", () -> {
            var clientId = clientIdResolver.requireClientId();
            LOG.info("[SET_AUDIENCE] Client: {}, EventId: {}, Audience size: {}",
                    clientId, request.getEventId(), request.getAudience());

            requireUuid(request.getEventId(), "event_id");
            validateAudience(request.getAudience());

            return useCase.setAudience(request, clientId);
        }, responseObserver);
    }

    @Override
    public void addRecipients(AddRecipientsRequest request, StreamObserver<AddRecipientsResponse> responseObserver) {
        execute("addRecipients", () -> {
            var clientId = clientIdResolver.requireClientId();
            LOG.info("[ADD_RECIPIENTS] Client: {}, EventId: {}, New recipients: {}, Key: {}",
                    clientId, request.getEventId(), request.getRecipientIdCount(), request.getIdempotencyKey());

            requireUuid(request.getEventId(), "event_id");
            requireNotBlank(request.getIdempotencyKey(), "idempotency_key");
            if (request.getRecipientIdCount() == 0) {
                throw Status.INVALID_ARGUMENT.withDescription("recipient_id must not be empty").asRuntimeException();
            }

            return useCase.addRecipients(request, clientId);
        }, responseObserver);
    }

    @Override
    public void removeRecipients(RemoveRecipientsRequest request, StreamObserver<RemoveRecipientsResponse> responseObserver) {
        execute("removeRecipients", () -> {
            var clientId = clientIdResolver.requireClientId();
            LOG.info("[REMOVE_RECIPIENTS] Client: {}, EventId: {}, Count: {}",
                    clientId, request.getEventId(), request.getRecipientIdCount());

            requireUuid(request.getEventId(), "event_id");
            if (request.getRecipientIdCount() == 0) {
                throw Status.INVALID_ARGUMENT.withDescription("recipient_id must not be empty").asRuntimeException();
            }

            return useCase.removeRecipients(request, clientId);
        }, responseObserver);
    }

    @Override
    public void getAudience(GetAudienceRequest request, StreamObserver<GetAudienceResponse> responseObserver) {
        execute("getAudience", () -> {
            var clientId = clientIdResolver.requireClientId();
            LOG.debug("[GET_AUDIENCE] Client: {}, EventId: {}", clientId, request.getEventId());

            requireUuid(request.getEventId(), "event_id");
            return useCase.getAudience(request, clientId);
        }, responseObserver);
    }

    // --- Dispatch Management ---

    @Override
    public void triggerDispatch(TriggerDispatchRequest request, StreamObserver<TriggerDispatchResponse> responseObserver) {
        execute("triggerDispatch", () -> {
            var clientId = clientIdResolver.requireClientId();
            LOG.info("[TRIGGER_DISPATCH] Client: {}, EventId: {}, Key: {}",
                    clientId, request.getEventId(), request.getIdempotencyKey());

            requireUuid(request.getEventId(), "event_id");
            requireNotBlank(request.getIdempotencyKey(), "idempotency_key");

            var resp = useCase.triggerDispatch(request, clientId);
            LOG.info("[TRIGGER_DISPATCH] Dispatch initiated. Id: {}", resp.getDispatchId());
            return resp;
        }, responseObserver);
    }

    @Override
    public void getDispatch(GetDispatchRequest request, StreamObserver<GetDispatchResponse> responseObserver) {
        execute("getDispatch", () -> {
            var clientId = clientIdResolver.requireClientId();
            LOG.debug("[GET_DISPATCH] Client: {}, DispatchId: {}", clientId, request.getDispatchId());

            requireUuid(request.getDispatchId(), "dispatch_id");
            return useCase.getDispatch(request, clientId);
        }, responseObserver);
    }

    @Override
    public void listDispatches(ListDispatchesRequest request, StreamObserver<ListDispatchesResponse> responseObserver) {
        execute("listDispatches", () -> {
            var clientId = clientIdResolver.requireClientId();
            LOG.info("[LIST_DISPATCHES] Client: {}, EventId: {}, Page: {}",
                    clientId, request.getEventId(), request.getPage());

            requireUuid(request.getEventId(), "event_id");
            validateList(request.getPage(), request.getSize());

            return useCase.listDispatches(request, clientId);
        }, responseObserver);
    }

    /**
     * Обертка для выполнения логики метода с полным логированием жизненного цикла запроса
     */
    private <T> void execute(String methodName, ServiceAction<T> action, StreamObserver<T> responseObserver) {
        try {
            T result = action.run();
            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (StatusRuntimeException e) {
            LOG.warn("[{}] Business error: {} | Status: {}", methodName, e.getMessage(), e.getStatus().getCode());
            responseObserver.onError(e);
        } catch (Exception e) {
            LOG.error("[{}] Unexpected system error: {}", methodName, e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Unexpected error: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @FunctionalInterface
    private interface ServiceAction<T> {
        T run() throws Exception;
    }
}