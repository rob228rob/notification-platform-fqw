package ru.batoyan.vkr.notification.templates.usecase;

import com.google.protobuf.Timestamp;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import ru.batoyan.vkr.notification.templates.render.TemplateRenderService;
import ru.batoyan.vkr.notification.templates.usecase.mongo.TemplateChannelContentDocument;
import ru.batoyan.vkr.notification.templates.usecase.mongo.TemplateDocument;
import ru.batoyan.vkr.notification.templates.usecase.mongo.TemplateMongoRepository;
import ru.batoyan.vkr.notification.templates.usecase.mongo.TemplateVersionDocument;
import ru.notification.common.proto.v1.Channel;
import ru.notification.templates.proto.v1.CreateTemplateRequest;
import ru.notification.templates.proto.v1.CreateTemplateResponse;
import ru.notification.templates.proto.v1.GetTemplateRequest;
import ru.notification.templates.proto.v1.GetTemplateResponse;
import ru.notification.templates.proto.v1.ListTemplatesRequest;
import ru.notification.templates.proto.v1.ListTemplatesResponse;
import ru.notification.templates.proto.v1.RenderPreviewRequest;
import ru.notification.templates.proto.v1.RenderPreviewResponse;
import ru.notification.templates.proto.v1.TemplateChannelContent;
import ru.notification.templates.proto.v1.TemplateEngine;
import ru.notification.templates.proto.v1.TemplateStatus;
import ru.notification.templates.proto.v1.TemplateVersionView;
import ru.notification.templates.proto.v1.TemplateView;
import ru.notification.templates.proto.v1.UpdateTemplateRequest;
import ru.notification.templates.proto.v1.UpdateTemplateResponse;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MongoTemplateRegistryUseCase implements TemplateRegistryUseCase {

    private static final int CREATE_VERSION = 1;
    private static final int MAX_UPDATE_RETRIES = 3;
    private static final long TEMPLATE_SCHEMA_VERSION = 1L;

    private final TemplateMongoRepository templateRepository;
    private final TemplateRenderService renderService;

    @Override
    public CreateTemplateResponse createTemplate(String clientId, CreateTemplateRequest request) {
        if (request.getContentsCount() == 0) {
            throw new IllegalArgumentException("contents is required");
        }

        if (!request.getIdempotencyKey().isBlank()) {
            var existingByIdempotency = templateRepository.findByClientIdAndCreateIdempotencyKey(clientId, request.getIdempotencyKey());
            if (existingByIdempotency.isPresent()) {
                return createResponse(existingByIdempotency.get());
            }
        }

        var now = Instant.now();
        var templateId = UUID.randomUUID().toString();
        var engine = normalizeEngine(request.getEngine());

        var document = TemplateDocument.builder()
                .templateId(templateId)
                .clientId(clientId)
                .name(request.getName())
                .description(request.getDescription())
                .status(TemplateStatus.TEMPLATE_STATUS_PUBLISHED.name())
                .activeVersion(CREATE_VERSION)
                .createIdempotencyKey(emptyToNull(request.getIdempotencyKey()))
                .schemaVersion(TEMPLATE_SCHEMA_VERSION)
                .createdAt(now)
                .updatedAt(now)
                .versions(List.of(newTemplateVersion(CREATE_VERSION, engine, request.getContentsList(), now)))
                .build();

        try {
            var saved = templateRepository.save(document);
            return createResponse(saved);
        } catch (DuplicateKeyException duplicateKeyException) {
            if (!request.getIdempotencyKey().isBlank()) {
                return templateRepository.findByClientIdAndCreateIdempotencyKey(clientId, request.getIdempotencyKey())
                        .map(this::createResponse)
                        .orElseThrow(() -> duplicateKeyException);
            }
            throw duplicateKeyException;
        }
    }

    @Override
    public UpdateTemplateResponse updateTemplate(String clientId, UpdateTemplateRequest request) {
        if (request.getContentsCount() == 0) {
            throw new IllegalArgumentException("contents is required");
        }

        var engine = normalizeEngine(request.getEngine());
        var now = Instant.now();

        for (int attempt = 1; attempt <= MAX_UPDATE_RETRIES; attempt++) {
            var current = findTemplateOrThrow(clientId, request.getTemplateId());
            var nextVersion = nextVersion(current.versions());
            var resolvedName = request.getName().isBlank() ? current.name() : request.getName();
            var resolvedDescription = request.getDescription().isBlank() ? current.description() : request.getDescription();

            var updated = current.toBuilder()
                    .name(resolvedName)
                    .description(resolvedDescription)
                    .status(TemplateStatus.TEMPLATE_STATUS_PUBLISHED.name())
                    .activeVersion(nextVersion)
                    .updatedAt(now)
                    .versions(appendVersion(current.versions(), newTemplateVersion(nextVersion, engine, request.getContentsList(), now)))
                    .build();

            try {
                templateRepository.save(updated);
                return UpdateTemplateResponse.newBuilder()
                        .setTemplateId(request.getTemplateId())
                        .setUpdatedVersion(nextVersion)
                        .setStatus(TemplateStatus.TEMPLATE_STATUS_PUBLISHED)
                        .build();
            } catch (OptimisticLockingFailureException ignored) {
                if (attempt == MAX_UPDATE_RETRIES) {
                    throw new IllegalStateException("Concurrent template update conflict, retry later");
                }
            }
        }

        throw new IllegalStateException("Template update retry loop ended unexpectedly");
    }

    @Override
    public GetTemplateResponse getTemplate(String clientId, GetTemplateRequest request) {
        var template = findTemplateOrThrow(clientId, request.getTemplateId());
        var versionNumber = resolveRequestedVersion(request.getVersion(), template.activeVersion());
        var version = findVersionOrThrow(template, versionNumber);

        return GetTemplateResponse.newBuilder()
                .setTemplate(toTemplateView(template))
                .setVersion(toTemplateVersionView(template.templateId(), version))
                .build();
    }

    @Override
    public ListTemplatesResponse listTemplates(String clientId, ListTemplatesRequest request) {
        var page = Math.max(0, request.getPage());
        var size = Math.max(1, Math.min(100, request.getSize() == 0 ? 20 : request.getSize()));
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));

        var records = request.getStatusFilter() == TemplateStatus.TEMPLATE_STATUS_UNSPECIFIED
                ? templateRepository.findByClientId(clientId, pageable)
                : templateRepository.findByClientIdAndStatus(clientId, request.getStatusFilter().name(), pageable);

        return ListTemplatesResponse.newBuilder()
                .addAllTemplates(records.stream().map(this::toTemplateView).toList())
                .setTotal(records.getTotalElements())
                .setPage(page)
                .setSize(size)
                .build();
    }

    @Override
    public RenderPreviewResponse renderPreview(String clientId, RenderPreviewRequest request) {
        var template = findTemplateOrThrow(clientId, request.getTemplateId());
        var versionNumber = resolveRequestedVersion(request.getVersion(), template.activeVersion());
        var version = findVersionOrThrow(template, versionNumber);

        var content = version.contents().stream()
                .filter(it -> it.channel().equals(request.getChannel().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Channel content not found"));

        Map<String, String> payload = request.getPayloadMap();
        var engine = templateEngineFromName(version.engine());
        var subject = renderService.render(engine, nullToEmpty(content.subject()), payload);
        var body = renderService.render(engine, nullToEmpty(content.body()), payload);

        return RenderPreviewResponse.newBuilder()
                .setSubject(subject)
                .setBody(body)
                .build();
    }

    private TemplateDocument findTemplateOrThrow(String clientId, String templateId) {
        return templateRepository.findByClientIdAndTemplateId(clientId, templateId)
                .orElseThrow(() -> new IllegalArgumentException("Template not found"));
    }

    private TemplateVersionDocument findVersionOrThrow(TemplateDocument template, int version) {
        return template.versions().stream()
                .filter(it -> it.version() == version)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Template version not found"));
    }

    private static int resolveRequestedVersion(int requestedVersion, int activeVersion) {
        var version = requestedVersion > 0 ? requestedVersion : activeVersion;
        if (version <= 0) {
            throw new IllegalArgumentException("version must be specified or active_version must exist");
        }
        return version;
    }

    private static int nextVersion(List<TemplateVersionDocument> versions) {
        return versions.stream().map(TemplateVersionDocument::version).max(Comparator.naturalOrder()).orElse(0) + 1;
    }

    private static List<TemplateVersionDocument> appendVersion(List<TemplateVersionDocument> source, TemplateVersionDocument newVersion) {
        var copy = new java.util.ArrayList<>(source);
        copy.add(newVersion);
        return List.copyOf(copy);
    }

    private static TemplateVersionDocument newTemplateVersion(
            int version,
            TemplateEngine engine,
            List<TemplateChannelContent> contents,
            Instant createdAt
    ) {
        return TemplateVersionDocument.builder()
                .version(version)
                .engine(engine.name())
                .contents(contents.stream().map(it -> TemplateChannelContentDocument.builder()
                        .channel(it.getChannel().name())
                        .subject(it.getSubject())
                        .body(it.getBody())
                        .build()).toList())
                .createdAt(createdAt)
                .build();
    }

    private static TemplateEngine normalizeEngine(TemplateEngine engine) {
        return engine == TemplateEngine.TEMPLATE_ENGINE_UNSPECIFIED
                ? TemplateEngine.TEMPLATE_ENGINE_HANDLEBARS
                : engine;
    }

    private static TemplateEngine templateEngineFromName(String name) {
        try {
            return TemplateEngine.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return TemplateEngine.TEMPLATE_ENGINE_HANDLEBARS;
        }
    }

    private CreateTemplateResponse createResponse(TemplateDocument template) {
        return CreateTemplateResponse.newBuilder()
                .setTemplateId(template.templateId())
                .setCreatedVersion(template.activeVersion())
                .setStatus(TemplateStatus.valueOf(template.status()))
                .build();
    }

    private TemplateView toTemplateView(TemplateDocument template) {
        return TemplateView.newBuilder()
                .setTemplateId(template.templateId())
                .setClientId(template.clientId())
                .setName(template.name())
                .setDescription(nullToEmpty(template.description()))
                .setStatus(TemplateStatus.valueOf(template.status()))
                .setActiveVersion(template.activeVersion())
                .setCreatedAt(toTs(template.createdAt()))
                .setUpdatedAt(toTs(template.updatedAt()))
                .build();
    }

    private TemplateVersionView toTemplateVersionView(String templateId, TemplateVersionDocument version) {
        return TemplateVersionView.newBuilder()
                .setTemplateId(templateId)
                .setVersion(version.version())
                .setEngine(templateEngineFromName(version.engine()))
                .addAllContents(version.contents().stream()
                        .map(it -> TemplateChannelContent.newBuilder()
                                .setChannel(Channel.valueOf(it.channel()))
                                .setSubject(nullToEmpty(it.subject()))
                                .setBody(nullToEmpty(it.body()))
                                .build())
                        .toList())
                .setCreatedAt(toTs(version.createdAt()))
                .build();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Timestamp toTs(Instant ts) {
        return Timestamp.newBuilder()
                .setSeconds(ts.getEpochSecond())
                .setNanos(ts.getNano())
                .build();
    }
}
