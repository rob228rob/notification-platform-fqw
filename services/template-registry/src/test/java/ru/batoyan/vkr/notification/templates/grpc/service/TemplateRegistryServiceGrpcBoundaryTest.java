package ru.batoyan.vkr.notification.templates.grpc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import ru.batoyan.vkr.notification.templates.helpers.ClientIdResolver;
import ru.batoyan.vkr.notification.templates.usecase.TemplateRegistryUseCase;
import ru.notification.templates.proto.v1.CreateTemplateRequest;
import ru.notification.templates.proto.v1.CreateTemplateResponse;
import ru.notification.templates.proto.v1.GetTemplateRequest;
import ru.notification.templates.proto.v1.GetTemplateResponse;
import ru.notification.templates.proto.v1.ListTemplatesRequest;
import ru.notification.templates.proto.v1.ListTemplatesResponse;
import ru.notification.templates.proto.v1.RenderPreviewRequest;
import ru.notification.templates.proto.v1.RenderPreviewResponse;
import ru.notification.templates.proto.v1.TemplateStatus;
import ru.notification.templates.proto.v1.UpdateTemplateRequest;
import ru.notification.templates.proto.v1.UpdateTemplateResponse;

class TemplateRegistryServiceGrpcBoundaryTest {

    @Test
    void createTemplateShouldDelegateToUseCase() {
        var fixture = new Fixture();
        when(fixture.useCase.createTemplate("client-1", CreateTemplateRequest.newBuilder().setName("name").build()))
                .thenReturn(CreateTemplateResponse.newBuilder()
                        .setTemplateId("tpl-1")
                        .setCreatedVersion(1)
                        .setStatus(TemplateStatus.TEMPLATE_STATUS_PUBLISHED)
                        .build());
        var observer = new TestObserver<CreateTemplateResponse>();

        fixture.grpc.createTemplate(CreateTemplateRequest.newBuilder().setName("name").build(), observer);

        assertThat(observer.value.getTemplateId()).isEqualTo("tpl-1");
        assertThat(observer.completed).isTrue();
        verify(fixture.useCase).createTemplate("client-1", CreateTemplateRequest.newBuilder().setName("name").build());
    }

    @Test
    void createTemplateShouldRejectBlankName() {
        var observer = new TestObserver<CreateTemplateResponse>();

        new Fixture().grpc.createTemplate(CreateTemplateRequest.newBuilder().build(), observer);

        assertThat(status(observer.error)).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void updateTemplateShouldRejectBlankTemplateId() {
        var observer = new TestObserver<UpdateTemplateResponse>();

        new Fixture().grpc.updateTemplate(UpdateTemplateRequest.newBuilder().setName("name").build(), observer);

        assertThat(status(observer.error)).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void getTemplateShouldDelegateToUseCase() {
        var fixture = new Fixture();
        var request = GetTemplateRequest.newBuilder().setTemplateId("tpl-1").build();
        when(fixture.useCase.getTemplate("client-1", request)).thenReturn(GetTemplateResponse.newBuilder().build());
        var observer = new TestObserver<GetTemplateResponse>();

        fixture.grpc.getTemplate(request, observer);

        assertThat(observer.completed).isTrue();
        verify(fixture.useCase).getTemplate("client-1", request);
    }

    @Test
    void listTemplatesShouldDelegateWithoutExtraValidation() {
        var fixture = new Fixture();
        var request = ListTemplatesRequest.newBuilder().setPage(0).setSize(20).build();
        when(fixture.useCase.listTemplates("client-1", request)).thenReturn(ListTemplatesResponse.newBuilder().setSize(20).build());
        var observer = new TestObserver<ListTemplatesResponse>();

        fixture.grpc.listTemplates(request, observer);

        assertThat(observer.value.getSize()).isEqualTo(20);
        verify(fixture.useCase).listTemplates("client-1", request);
    }

    @Test
    void renderPreviewShouldMapUseCaseValidationErrorToInvalidArgument() {
        var fixture = new Fixture();
        var request = RenderPreviewRequest.newBuilder().setTemplateId("tpl-1").build();
        when(fixture.useCase.renderPreview("client-1", request)).thenThrow(new IllegalArgumentException("bad payload"));
        var observer = new TestObserver<RenderPreviewResponse>();

        fixture.grpc.renderPreview(request, observer);

        assertThat(status(observer.error)).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void renderPreviewShouldRejectBlankTemplateId() {
        var observer = new TestObserver<RenderPreviewResponse>();

        new Fixture().grpc.renderPreview(RenderPreviewRequest.newBuilder().build(), observer);

        assertThat(status(observer.error)).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    private static final class Fixture {
        private final TemplateRegistryUseCase useCase = mock(TemplateRegistryUseCase.class);
        private final ClientIdResolver clientIdResolver = mock(ClientIdResolver.class);
        private final TemplateRegistryService grpc = new TemplateRegistryService(useCase, clientIdResolver);

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
