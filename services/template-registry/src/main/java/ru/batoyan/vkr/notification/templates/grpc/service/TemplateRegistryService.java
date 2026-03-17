package ru.batoyan.vkr.notification.templates.grpc.service;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ru.batoyan.vkr.notification.templates.helpers.ClientIdResolver;
import ru.batoyan.vkr.notification.templates.usecase.TemplateRegistryUseCase;
import ru.notification.templates.proto.v1.TemplateRegistryGrpc;
import ru.notification.templates.proto.v1.*;

import static ru.batoyan.vkr.notification.templates.helpers.Validations.requireNotBlank;

@GrpcService
public class TemplateRegistryService extends TemplateRegistryGrpc.TemplateRegistryImplBase {
    private static final Logger LOG = LogManager.getLogger();

    private final TemplateRegistryUseCase useCase;
    private final ClientIdResolver clientIdResolver;

    public TemplateRegistryService(TemplateRegistryUseCase useCase, ClientIdResolver clientIdResolver) {
        this.useCase = useCase;
        this.clientIdResolver = clientIdResolver;
    }

    @Override
    public void createTemplate(CreateTemplateRequest request, StreamObserver<CreateTemplateResponse> responseObserver) {
        execute("CreateTemplate", () -> {
            requireNotBlank(request.getName(), "name");
            var clientId = clientIdResolver.requireClientId();
            return useCase.createTemplate(clientId, request);
        }, responseObserver);
    }

    @Override
    public void updateTemplate(UpdateTemplateRequest request, StreamObserver<UpdateTemplateResponse> responseObserver) {
        execute("UpdateTemplate", () -> {
            requireNotBlank(request.getTemplateId(), "template_id");
            requireNotBlank(request.getName(), "name");
            var clientId = clientIdResolver.requireClientId();
            return useCase.updateTemplate(clientId, request);
        }, responseObserver);
    }

    @Override
    public void getTemplate(GetTemplateRequest request, StreamObserver<GetTemplateResponse> responseObserver) {
        execute("GetTemplate", () -> {
            requireNotBlank(request.getTemplateId(), "template_id");
            var clientId = clientIdResolver.requireClientId();
            return useCase.getTemplate(clientId, request);
        }, responseObserver);
    }

    @Override
    public void listTemplates(ListTemplatesRequest request, StreamObserver<ListTemplatesResponse> responseObserver) {
        execute("ListTemplates", () -> {
            var clientId = clientIdResolver.requireClientId();
            return useCase.listTemplates(clientId, request);
        }, responseObserver);
    }

    @Override
    public void renderPreview(RenderPreviewRequest request, StreamObserver<RenderPreviewResponse> responseObserver) {
        execute("RenderPreview", () -> {
            requireNotBlank(request.getTemplateId(), "template_id");
            var clientId = clientIdResolver.requireClientId();
            return useCase.renderPreview(clientId, request);
        }, responseObserver);
    }

    private <T> void execute(String methodName, ServiceAction<T> action, StreamObserver<T> responseObserver) {
        try {
            T result = action.run();
            responseObserver.onNext(result);
            responseObserver.onCompleted();
        } catch (StatusRuntimeException e) {
            LOG.warn("[{}] Business error: {} | Status: {}", methodName, e.getMessage(), e.getStatus().getCode());
            responseObserver.onError(e);
        } catch (IllegalArgumentException e) {
            LOG.warn("[{}] Validation error: {}", methodName, e.getMessage());
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
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
