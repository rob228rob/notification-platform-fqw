package ru.batoyan.vkr.notification.templates.usecase;

import org.junit.jupiter.api.Test;
import ru.batoyan.vkr.notification.templates.render.SimpleTemplateRenderer;
import ru.batoyan.vkr.notification.templates.render.TemplateRenderService;
import ru.batoyan.vkr.notification.templates.usecase.mongo.TemplateChannelContentDocument;
import ru.batoyan.vkr.notification.templates.usecase.mongo.TemplateDocument;
import ru.batoyan.vkr.notification.templates.usecase.mongo.TemplateMongoRepository;
import ru.batoyan.vkr.notification.templates.usecase.mongo.TemplateVersionDocument;
import ru.notification.common.proto.v1.Channel;
import ru.notification.templates.proto.v1.CreateTemplateRequest;
import ru.notification.templates.proto.v1.RenderPreviewRequest;
import ru.notification.templates.proto.v1.TemplateChannelContent;
import ru.notification.templates.proto.v1.TemplateEngine;
import ru.notification.templates.proto.v1.TemplateStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoTemplateRegistryUseCaseTest {

    @Test
    void createTemplateReturnsExistingOnIdempotencyKeyHit() {
        var repository = mock(TemplateMongoRepository.class);
        var useCase = new MongoTemplateRegistryUseCase(repository, new TemplateRenderService(List.of(new SimpleTemplateRenderer())));

        var existing = TemplateDocument.builder()
                .templateId("tpl-1")
                .clientId("client-1")
                .name("test")
                .description("desc")
                .status(TemplateStatus.TEMPLATE_STATUS_PUBLISHED.name())
                .activeVersion(1)
                .schemaVersion(1L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .versions(List.of())
                .build();

        when(repository.findByClientIdAndCreateIdempotencyKey("client-1", "idem-1")).thenReturn(Optional.of(existing));

        var response = useCase.createTemplate("client-1", CreateTemplateRequest.newBuilder()
                .setIdempotencyKey("idem-1")
                .setName("Template")
                .setEngine(TemplateEngine.TEMPLATE_ENGINE_FREEMARKER)
                .addContents(TemplateChannelContent.newBuilder()
                        .setChannel(Channel.CHANNEL_EMAIL)
                        .setSubject("s")
                        .setBody("b")
                        .build())
                .build());

        assertEquals("tpl-1", response.getTemplateId());
        assertEquals(1, response.getCreatedVersion());
        verify(repository, never()).save(any());
    }

    @Test
    void renderPreviewUsesActiveVersionAndPayload() {
        var repository = mock(TemplateMongoRepository.class);
        var useCase = new MongoTemplateRegistryUseCase(repository, new TemplateRenderService(List.of(new SimpleTemplateRenderer())));

        var document = TemplateDocument.builder()
                .templateId("tpl-2")
                .clientId("client-2")
                .name("mail")
                .description("")
                .status(TemplateStatus.TEMPLATE_STATUS_PUBLISHED.name())
                .activeVersion(2)
                .schemaVersion(1L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .versions(List.of(
                        TemplateVersionDocument.builder()
                                .version(2)
                                .engine(TemplateEngine.TEMPLATE_ENGINE_FREEMARKER.name())
                                .createdAt(Instant.now())
                                .contents(List.of(TemplateChannelContentDocument.builder()
                                        .channel(Channel.CHANNEL_EMAIL.name())
                                        .subject("Hello ${name}")
                                        .body("Body for ${name}")
                                        .build()))
                                .build()))
                .build();

        when(repository.findByClientIdAndTemplateId("client-2", "tpl-2")).thenReturn(Optional.of(document));

        var preview = useCase.renderPreview("client-2", RenderPreviewRequest.newBuilder()
                .setTemplateId("tpl-2")
                .setChannel(Channel.CHANNEL_EMAIL)
                .putAllPayload(Map.of("name", "Ivan"))
                .build());

        assertEquals("Hello Ivan", preview.getSubject());
        assertEquals("Body for Ivan", preview.getBody());
        verify(repository).findByClientIdAndTemplateId(eq("client-2"), eq("tpl-2"));
    }
}
