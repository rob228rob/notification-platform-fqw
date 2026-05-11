package ru.batoyan.vkr.notification.templates.usecase;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void createTemplatePersistsFirstPublishedVersion() {
        var repository = mock(TemplateMongoRepository.class);
        var useCase = new MongoTemplateRegistryUseCase(repository, new TemplateRenderService(List.of(new SimpleTemplateRenderer())));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = useCase.createTemplate("client-1", CreateTemplateRequest.newBuilder()
                .setName("Welcome")
                .setEngine(TemplateEngine.TEMPLATE_ENGINE_FREEMARKER)
                .addContents(emailContent("Subject", "Body"))
                .build());

        assertThat(response.getCreatedVersion()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(TemplateStatus.TEMPLATE_STATUS_PUBLISHED);
        verify(repository).save(any(TemplateDocument.class));
    }

    @Test
    void createTemplateRejectsEmptyChannelContents() {
        var useCase = new MongoTemplateRegistryUseCase(
                mock(TemplateMongoRepository.class),
                new TemplateRenderService(List.of(new SimpleTemplateRenderer()))
        );

        assertThatThrownBy(() -> useCase.createTemplate("client-1", CreateTemplateRequest.newBuilder().build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contents");
    }

    @Test
    void updateTemplateAppendsNextVersion() {
        var repository = mock(TemplateMongoRepository.class);
        var useCase = new MongoTemplateRegistryUseCase(repository, new TemplateRenderService(List.of(new SimpleTemplateRenderer())));
        var current = templateDocument("tpl-3", 1, Channel.CHANNEL_EMAIL);
        when(repository.findByClientIdAndTemplateId("client-1", "tpl-3")).thenReturn(Optional.of(current));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = useCase.updateTemplate("client-1", ru.notification.templates.proto.v1.UpdateTemplateRequest.newBuilder()
                .setTemplateId("tpl-3")
                .setName("Updated")
                .addContents(emailContent("New ${name}", "Body ${name}"))
                .build());

        assertThat(response.getUpdatedVersion()).isEqualTo(2);
        assertThat(response.getStatus()).isEqualTo(TemplateStatus.TEMPLATE_STATUS_PUBLISHED);
    }

    @Test
    void updateTemplateRetriesOptimisticLockConflict() {
        var repository = mock(TemplateMongoRepository.class);
        var useCase = new MongoTemplateRegistryUseCase(repository, new TemplateRenderService(List.of(new SimpleTemplateRenderer())));
        when(repository.findByClientIdAndTemplateId("client-1", "tpl-4"))
                .thenReturn(Optional.of(templateDocument("tpl-4", 1, Channel.CHANNEL_EMAIL)));
        when(repository.save(any()))
                .thenThrow(new OptimisticLockingFailureException("conflict"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = useCase.updateTemplate("client-1", ru.notification.templates.proto.v1.UpdateTemplateRequest.newBuilder()
                .setTemplateId("tpl-4")
                .addContents(emailContent("Subject", "Body"))
                .build());

        assertThat(response.getUpdatedVersion()).isEqualTo(2);
        verify(repository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void getTemplateUsesActiveVersionWhenRequestVersionIsZero() {
        var repository = mock(TemplateMongoRepository.class);
        var useCase = new MongoTemplateRegistryUseCase(repository, new TemplateRenderService(List.of(new SimpleTemplateRenderer())));
        when(repository.findByClientIdAndTemplateId("client-1", "tpl-5"))
                .thenReturn(Optional.of(templateDocument("tpl-5", 3, Channel.CHANNEL_SMS)));

        var response = useCase.getTemplate("client-1", ru.notification.templates.proto.v1.GetTemplateRequest.newBuilder()
                .setTemplateId("tpl-5")
                .build());

        assertThat(response.getVersion().getVersion()).isEqualTo(3);
        assertThat(response.getVersion().getContents(0).getChannel()).isEqualTo(Channel.CHANNEL_SMS);
    }

    @Test
    void renderPreviewRejectsUnknownChannelContent() {
        var repository = mock(TemplateMongoRepository.class);
        var useCase = new MongoTemplateRegistryUseCase(repository, new TemplateRenderService(List.of(new SimpleTemplateRenderer())));
        when(repository.findByClientIdAndTemplateId("client-1", "tpl-6"))
                .thenReturn(Optional.of(templateDocument("tpl-6", 1, Channel.CHANNEL_EMAIL)));

        assertThatThrownBy(() -> useCase.renderPreview("client-1", RenderPreviewRequest.newBuilder()
                .setTemplateId("tpl-6")
                .setChannel(Channel.CHANNEL_SMS)
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Channel content");
    }

    @Test
    void listTemplatesClampsPageSizeAndReturnsDocuments() {
        var repository = mock(TemplateMongoRepository.class);
        var useCase = new MongoTemplateRegistryUseCase(repository, new TemplateRenderService(List.of(new SimpleTemplateRenderer())));
        when(repository.findByClientId(eq("client-1"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(templateDocument("tpl-7", 1, Channel.CHANNEL_EMAIL))));

        var response = useCase.listTemplates("client-1", ru.notification.templates.proto.v1.ListTemplatesRequest.newBuilder()
                .setPage(-1)
                .setSize(0)
                .build());

        assertThat(response.getTemplatesCount()).isEqualTo(1);
        assertThat(response.getPage()).isZero();
        assertThat(response.getSize()).isEqualTo(20);
    }

    private static TemplateChannelContent emailContent(String subject, String body) {
        return TemplateChannelContent.newBuilder()
                .setChannel(Channel.CHANNEL_EMAIL)
                .setSubject(subject)
                .setBody(body)
                .build();
    }

    private static TemplateDocument templateDocument(String templateId, int version, Channel channel) {
        return TemplateDocument.builder()
                .templateId(templateId)
                .clientId("client-1")
                .name("template")
                .description("description")
                .status(TemplateStatus.TEMPLATE_STATUS_PUBLISHED.name())
                .activeVersion(version)
                .schemaVersion(1L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .versions(List.of(TemplateVersionDocument.builder()
                        .version(version)
                        .engine(TemplateEngine.TEMPLATE_ENGINE_FREEMARKER.name())
                        .createdAt(Instant.now())
                        .contents(List.of(TemplateChannelContentDocument.builder()
                                .channel(channel.name())
                                .subject("Hello ${name}")
                                .body("Body ${name}")
                                .build()))
                        .build()))
                .build();
    }
}
