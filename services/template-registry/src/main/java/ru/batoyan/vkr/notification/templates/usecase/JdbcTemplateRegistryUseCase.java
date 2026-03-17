package ru.batoyan.vkr.notification.templates.usecase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ru.notification.common.proto.v1.Channel;
import ru.notification.templates.proto.v1.*;
import ru.batoyan.vkr.notification.templates.render.TemplateRenderService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JdbcTemplateRegistryUseCase implements TemplateRegistryUseCase {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TemplateRenderService renderService;

    @Override
    public CreateTemplateResponse createTemplate(String clientId, CreateTemplateRequest request) {
        var templateId = UUID.randomUUID();
        var version = 1;
        var now = OffsetDateTime.now();
        var engine = request.getEngine() == TemplateEngine.TEMPLATE_ENGINE_UNSPECIFIED
                ? TemplateEngine.TEMPLATE_ENGINE_HANDLEBARS
                : request.getEngine();

        if (request.getContentsCount() == 0) {
            throw new IllegalArgumentException("contents is required");
        }
        var contentsJson = toJson(request.getContentsList());

        try {
            jdbc.update("""
                    insert into nf.template(template_id, client_id, name, description, status, active_version, idempotency_key, created_at, updated_at)
                    values (:template_id, :client_id, :name, :description, :status, :active_version, :idem, :now, :now)
                    """, new MapSqlParameterSource()
                    .addValue("template_id", templateId)
                    .addValue("client_id", UUID.fromString(clientId))
                    .addValue("name", request.getName())
                    .addValue("description", request.getDescription())
                    .addValue("status", TemplateStatus.TEMPLATE_STATUS_PUBLISHED.name())
                    .addValue("active_version", version)
                    .addValue("idem", request.getIdempotencyKey())
                    .addValue("now", now));
        } catch (DuplicateKeyException ignored) {
            // idempotent create, fetch existing
            return getTemplate(clientId, GetTemplateRequest.newBuilder().setTemplateId(templateId.toString()).build())
                    .getTemplate().getStatus() == TemplateStatus.TEMPLATE_STATUS_PUBLISHED
                    ? CreateTemplateResponse.newBuilder().setTemplateId(templateId.toString()).setCreatedVersion(version).setStatus(TemplateStatus.TEMPLATE_STATUS_PUBLISHED).build()
                    : CreateTemplateResponse.newBuilder().setTemplateId(templateId.toString()).setCreatedVersion(version).setStatus(TemplateStatus.TEMPLATE_STATUS_PUBLISHED).build();
        }

        jdbc.update("""
                insert into nf.template_version(template_id, version, engine, contents, created_at)
                values (:template_id, :version, :engine, cast(:contents as jsonb), :now)
                """, new MapSqlParameterSource()
                .addValue("template_id", templateId)
                .addValue("version", version)
                .addValue("engine", engine.name())
                .addValue("contents", contentsJson)
                .addValue("now", now));

        return CreateTemplateResponse.newBuilder()
                .setTemplateId(templateId.toString())
                .setCreatedVersion(version)
                .setStatus(TemplateStatus.TEMPLATE_STATUS_PUBLISHED)
                .build();
    }

    @Override
    public UpdateTemplateResponse updateTemplate(String clientId, UpdateTemplateRequest request) {
        if (request.getContentsCount() == 0) {
            throw new IllegalArgumentException("contents is required");
        }
        var templateId = UUID.fromString(request.getTemplateId());
        var now = OffsetDateTime.now();
        var engine = request.getEngine() == TemplateEngine.TEMPLATE_ENGINE_UNSPECIFIED
                ? TemplateEngine.TEMPLATE_ENGINE_HANDLEBARS
                : request.getEngine();

        // ensure template exists and get current fields
        var existing = jdbc.query("""
                select template_id, name, description, active_version
                from nf.template
                where template_id = :template_id and client_id = :client_id
                """, Map.of("template_id", templateId, "client_id", UUID.fromString(clientId)),
                (rs, n) -> Map.of(
                        "name", rs.getString("name"),
                        "description", rs.getString("description"),
                        "active_version", rs.getInt("active_version")
                )).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Template not found"));

        var nextVersion = jdbc.queryForObject("""
                select coalesce(max(version), 0) + 1 from nf.template_version where template_id = :template_id
                """, Map.of("template_id", templateId), Integer.class);

        var contentsJson = toJson(request.getContentsList());

        jdbc.update("""
                insert into nf.template_version(template_id, version, engine, contents, created_at)
                values (:template_id, :version, :engine, cast(:contents as jsonb), :now)
                """, new MapSqlParameterSource()
                .addValue("template_id", templateId)
                .addValue("version", nextVersion)
                .addValue("engine", engine.name())
                .addValue("contents", contentsJson)
                .addValue("now", now));

        jdbc.update("""
                update nf.template
                set name = :name,
                    description = :description,
                    status = :status,
                    active_version = :active_version,
                    updated_at = :now
                where template_id = :template_id and client_id = :client_id
                """, new MapSqlParameterSource()
                .addValue("name", request.getName().isBlank() ? existing.get("name") : request.getName())
                .addValue("description", request.getDescription().isBlank() ? existing.get("description") : request.getDescription())
                .addValue("status", TemplateStatus.TEMPLATE_STATUS_PUBLISHED.name())
                .addValue("active_version", nextVersion)
                .addValue("now", now)
                .addValue("template_id", templateId)
                .addValue("client_id", UUID.fromString(clientId)));

        return UpdateTemplateResponse.newBuilder()
                .setTemplateId(request.getTemplateId())
                .setUpdatedVersion(nextVersion)
                .setStatus(TemplateStatus.TEMPLATE_STATUS_PUBLISHED)
                .build();
    }

    @Override
    public GetTemplateResponse getTemplate(String clientId, GetTemplateRequest request) {
        var templateId = UUID.fromString(request.getTemplateId());
        var tpl = jdbc.query("""
                select template_id, client_id, name, description, status, active_version, created_at, updated_at
                from nf.template
                where template_id = :template_id and client_id = :client_id
                """, Map.of("template_id", templateId, "client_id", UUID.fromString(clientId)),
                (rs, n) -> TemplateView.newBuilder()
                        .setTemplateId(rs.getString("template_id"))
                        .setClientId(rs.getString("client_id"))
                        .setName(rs.getString("name"))
                        .setDescription(nullToEmpty(rs.getString("description")))
                        .setStatus(TemplateStatus.valueOf(rs.getString("status")))
                        .setActiveVersion(rs.getObject("active_version") == null ? 0 : rs.getInt("active_version"))
                        .setCreatedAt(toTs(rs.getObject("created_at", OffsetDateTime.class)))
                        .setUpdatedAt(toTs(rs.getObject("updated_at", OffsetDateTime.class)))
                        .build()).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Template not found"));

        int version = request.getVersion() > 0 ? request.getVersion() : tpl.getActiveVersion();
        if (version <= 0) {
            throw new IllegalArgumentException("version must be specified or active_version must exist");
        }
        var ver = fetchVersion(templateId, version);

        return GetTemplateResponse.newBuilder()
                .setTemplate(tpl)
                .setVersion(ver)
                .build();
    }

    @Override
    public ListTemplatesResponse listTemplates(String clientId, ListTemplatesRequest request) {
        var page = Math.max(0, request.getPage());
        var size = Math.max(1, Math.min(100, request.getSize() == 0 ? 20 : request.getSize()));

        var filterStatus = request.getStatusFilter() == TemplateStatus.TEMPLATE_STATUS_UNSPECIFIED
                ? null
                : request.getStatusFilter().name();
        var params = new MapSqlParameterSource()
                .addValue("client_id", UUID.fromString(clientId))
                .addValue("limit", size)
                .addValue("offset", page * size);
        var where = new StringBuilder("where client_id = :client_id");
        if (filterStatus != null) {
            where.append(" and status = :status");
            params.addValue("status", filterStatus);
        }

        var total = jdbc.queryForObject("select count(*) from nf.template " + where + " ", params, Long.class);
        var query = """
                select template_id, client_id, name, description, status, active_version, created_at, updated_at
                from nf.template
                %s
                order by updated_at desc
                limit :limit offset :offset
                """.formatted(where.toString());

        var items = jdbc.query(query, params, (rs, n) -> TemplateView.newBuilder()
                .setTemplateId(rs.getString("template_id"))
                .setClientId(rs.getString("client_id"))
                .setName(rs.getString("name"))
                .setDescription(nullToEmpty(rs.getString("description")))
                .setStatus(TemplateStatus.valueOf(rs.getString("status")))
                .setActiveVersion(rs.getObject("active_version") == null ? 0 : rs.getInt("active_version"))
                .setCreatedAt(toTs(rs.getObject("created_at", OffsetDateTime.class)))
                .setUpdatedAt(toTs(rs.getObject("updated_at", OffsetDateTime.class)))
                .build());

        return ListTemplatesResponse.newBuilder()
                .addAllTemplates(items)
                .setTotal(total == null ? 0 : total)
                .setPage(page)
                .setSize(size)
                .build();
    }


    @Override
    public RenderPreviewResponse renderPreview(String clientId, RenderPreviewRequest request) {
        var templateId = UUID.fromString(request.getTemplateId());
        var version = request.getVersion();
        var ver = version > 0
                ? fetchVersion(templateId, version)
                : fetchVersion(templateId, getActiveVersion(templateId));

        var channel = request.getChannel();
        var contents = ver.getContentsList().stream()
                .filter(c -> c.getChannel() == channel)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Channel content not found"));

        var payload = request.getPayloadMap();
        var engine = ver.getEngine();
        var subject = renderService.render(engine, contents.getSubject(), payload);
        var body = renderService.render(engine, contents.getBody(), payload);

        return RenderPreviewResponse.newBuilder()
                .setSubject(subject)
                .setBody(body)
                .build();
    }

    private TemplateVersionView fetchVersion(UUID templateId, int version) {
        var ver = jdbc.query("""
                select template_id, version, engine, contents, created_at
                from nf.template_version
                where template_id = :template_id and version = :version
                """, Map.of("template_id", templateId, "version", version),
                (rs, n) -> TemplateVersionView.newBuilder()
                        .setTemplateId(rs.getString("template_id"))
                        .setVersion(rs.getInt("version"))
                        .setEngine(TemplateEngine.valueOf(rs.getString("engine")))
                        .addAllContents(readContents(rs.getString("contents")))
                        .setCreatedAt(toTs(rs.getObject("created_at", OffsetDateTime.class)))
                        .build()).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Template version not found"));
        return ver;
    }

    private int getActiveVersion(UUID templateId) {
        Integer v = jdbc.queryForObject("""
                select active_version from nf.template where template_id = :template_id
                """, Map.of("template_id", templateId), Integer.class);
        if (v == null || v <= 0) {
            throw new IllegalArgumentException("Active version not set for template " + templateId);
        }
        return v;
    }

    private List<TemplateChannelContent> readContents(String json) {
        try {
            var list = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            return list.stream()
                    .map(map -> TemplateChannelContent.newBuilder()
                            .setChannel(Channel.valueOf(((String) map.get("channel")).toUpperCase()))
                            .setSubject((String) map.getOrDefault("subject", ""))
                            .setBody((String) map.getOrDefault("body", ""))
                            .build())
                    .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read contents", e);
        }
    }

    private String toJson(List<TemplateChannelContent> contents) {
        try {
            var plain = contents.stream()
                    .map(c -> Map.of(
                            "channel", c.getChannel().name(),
                            "subject", c.getSubject(),
                            "body", c.getBody()
                    ))
                    .toList();
            return objectMapper.writeValueAsString(plain);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid contents", e);
        }    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    private static com.google.protobuf.Timestamp toTs(OffsetDateTime ts) {
        return com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(ts.toEpochSecond())
                .setNanos(ts.getNano())
                .build();
    }
}
