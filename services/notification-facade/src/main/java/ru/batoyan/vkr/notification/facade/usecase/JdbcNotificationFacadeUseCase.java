package ru.batoyan.vkr.notification.facade.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.batoyan.vkr.notification.facade.template.TemplateRegistryRenderClient;
import ru.notification.common.proto.v1.Channel;
import ru.notification.common.proto.v1.DeliveryPriority;
import ru.notification.facade.proto.v1.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class JdbcNotificationFacadeUseCase implements NotificationFacadeUseCase {

    private static final Logger LOG = LogManager.getLogger();

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TemplateRegistryRenderClient templateRegistryRenderClient;

    public JdbcNotificationFacadeUseCase(NamedParameterJdbcTemplate jdbc,
                                         ObjectMapper objectMapper,
                                         TemplateRegistryRenderClient templateRegistryRenderClient) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.templateRegistryRenderClient = templateRegistryRenderClient;
    }

    @Override
    @Transactional
    public CreateEventResponse create(CreateEventRequest req, String clientId) {
        var clientUuid = parseClientId(clientId);

        var existing = findEventByClientAndIdem(clientUuid, req.getIdempotencyKey());
        if (existing.isPresent()) {
            var row = existing.get();
            return CreateEventResponse.newBuilder()
                    .setEventId(row.eventId.toString())
                    .setStatus(EventStatus.valueOf(row.status))
                    .setDeduplicated(true)
                    .setCreatedAt(toTs(row.createdAt))
                    .build();
        }

        var eventId = UUID.randomUUID();
        var now = Instant.now();

        var sk = req.getStrategy().getKind();
        var scheduledAt = (sk == StrategyKind.STRATEGY_KIND_SCHEDULED)
                ? OffsetDateTime.ofInstant(toInstant(req.getStrategy().getSendAt()), ZoneOffset.UTC)
                : null;

        var status = (sk == StrategyKind.STRATEGY_KIND_SCHEDULED)
                ? EventStatus.EVENT_STATUS_SCHEDULED.name()
                : EventStatus.EVENT_STATUS_DRAFT.name();

        var payload = resolveDeliveryPayload(
                clientId,
                req.getTemplateId(),
                req.getTemplateVersion(),
                req.getPreferredChannel(),
                req.getPayloadMap()
        );
        var payloadJson = toJson(payload);

        var sql = """
                insert into notification_event(
                  event_id, client_id, idempotency_key,
                  template_id, template_version,
                  priority, preferred_channel,
                  strategy_kind, scheduled_at,
                  status, payload,
                  created_at, updated_at
                ) values (
                  :event_id, :client_id, :idempotency_key,
                  :template_id, :template_version,
                  :priority, :preferred_channel,
                  :strategy_kind, :scheduled_at,
                  :status, cast(:payload as jsonb),
                  now(), now()
                ) on conflict (idempotency_key, client_id) do nothing
                """;

        var params = new HashMap<String, Object>();
        params.put("event_id", eventId);
        params.put("client_id", clientUuid);
        params.put("idempotency_key", req.getIdempotencyKey());
        params.put("template_id", req.getTemplateId());
        params.put("template_version", req.getTemplateVersion());
        params.put("priority", req.getPriority().name());
        params.put("preferred_channel", req.getPreferredChannel().name());
        params.put("strategy_kind", sk.name());
        params.put("scheduled_at", scheduledAt);
        params.put("status", status);
        params.put("payload", payloadJson);

        try {
            jdbc.update(sql, params);
        } catch (Exception e) {
            // если гонка — второй поток мог вставить, читаем и возвращаем dedup
            var race = findEventByClientAndIdem(clientUuid, req.getIdempotencyKey());
            if (race.isPresent()) {
                var row = race.get();
                return CreateEventResponse.newBuilder()
                        .setEventId(row.eventId.toString())
                        .setStatus(EventStatus.valueOf(row.status))
                        .setDeduplicated(true)
                        .setCreatedAt(toTs(row.createdAt))
                        .build();
            }
            throw Status.INTERNAL.withDescription("Failed to create event").withCause(e).asRuntimeException();
        }

        if (req.hasAudience()) {
            upsertAudience(eventId, req.getAudience());
        } else {
            // по умолчанию аудитория EXPLICIT пустая (TODO(rbatoyan): можно добавить потом батчами)
            upsertAudience(eventId, Audience.newBuilder()
                    .setKind(AudienceKind.AUDIENCE_KIND_EXPLICIT)
                    .setSnapshotOnDispatch(true)
                    .build());
        }

        return CreateEventResponse.newBuilder()
                .setEventId(eventId.toString())
                .setStatus(EventStatus.valueOf(status))
                .setDeduplicated(false)
                .setCreatedAt(toTs(now))
                .build();
    }

    @Override
    @Transactional
    public UpdateEventResponse update(UpdateEventRequest req, String clientId) {
        var clientUuid = parseClientId(clientId);
        var eventId = parseUuid(req.getEventId(), "event_id");

        var row = findEventByIdAndClient(eventId, clientUuid)
                .orElseThrow(() -> Status.NOT_FOUND.withDescription("event not found").asRuntimeException());

        if (!isMutableStatus(row.status)) {
            throw Status.FAILED_PRECONDITION.withDescription("event status is not mutable: " + row.status).asRuntimeException();
        }

        var mask = req.getUpdateMask();
        if (mask == null || mask.getPathsCount() == 0) {
            throw Status.INVALID_ARGUMENT.withDescription("update_mask.paths must not be empty").asRuntimeException();
        }

        var patch = new LinkedHashMap<String, Object>();
        var paths = new HashSet<>(mask.getPathsList());
        var templateVersion = row.templateVersion;
        var preferredChannel = Channel.valueOf(row.preferredChannel);
        Map<String, String> payload = null;

        for (var path : mask.getPathsList()) {
            switch (path) {
                case "template_version" -> {
                    templateVersion = req.getTemplateVersion();
                    patch.put("template_version", templateVersion);
                }
                case "priority" -> patch.put("priority", req.getPriority().name());
                case "preferred_channel" -> {
                    preferredChannel = req.getPreferredChannel();
                    patch.put("preferred_channel", preferredChannel.name());
                }
                case "strategy" -> {
                    var sk = req.getStrategy().getKind();
                    patch.put("strategy_kind", sk.name());
                    Instant scheduledAt = (sk == StrategyKind.STRATEGY_KIND_SCHEDULED)
                            ? toInstant(req.getStrategy().getSendAt())
                            : null;
                    patch.put("scheduled_at", scheduledAt);

                    // если ставим scheduled -> SCHEDULED, если убираем -> DRAFT/READY оставляем как есть
                    if (sk == StrategyKind.STRATEGY_KIND_SCHEDULED) {
                        patch.put("status", EventStatus.EVENT_STATUS_SCHEDULED.name());
                    }
                }
                case "payload" -> payload = new LinkedHashMap<>(req.getPayloadMap());
                default ->
                        throw Status.INVALID_ARGUMENT.withDescription("forbidden update_mask path: " + path).asRuntimeException();
            }
        }

        if (paths.contains("payload") || paths.contains("template_version") || paths.contains("preferred_channel")) {
            if (payload == null) {
                payload = readPayloadMap(row.payloadJson);
            }
            payload = resolveDeliveryPayload(
                    clientId,
                    row.templateId,
                    templateVersion,
                    preferredChannel,
                    payload
            );
            patch.put("payload", toJson(payload));
        }

        var sets = new ArrayList<String>();
        var params = new HashMap<String, Object>();
        params.put("event_id", eventId);
        params.put("client_id", clientUuid);
        params.put("updated_at", Instant.now());
        sets.add("updated_at = :updated_at");

        for (var e : patch.entrySet()) {
            if (e.getKey().equals("payload")) {
                sets.add("payload = cast(:payload as jsonb)");
                params.put("payload", e.getValue());
            } else {
                sets.add(e.getKey() + " = :" + e.getKey());
                params.put(e.getKey(), e.getValue());
            }
        }

        var sql = "update notification_event set " + String.join(", ", sets)
                + " where event_id = :event_id and client_id = :client_id"
                + " and status in ('EVENT_STATUS_DRAFT','EVENT_STATUS_READY','EVENT_STATUS_SCHEDULED')";

        int updated = jdbc.update(sql, params);
        if (updated == 0) {
            throw Status.FAILED_PRECONDITION.withDescription("event not updated (status changed?)").asRuntimeException();
        }

        var updatedRow = findEventByIdAndClient(eventId, clientUuid)
                .orElseThrow(() -> Status.INTERNAL.withDescription("event disappeared after update").asRuntimeException());

        return UpdateEventResponse.newBuilder()
                .setEventId(updatedRow.eventId.toString())
                .setStatus(EventStatus.valueOf(updatedRow.status))
                .setUpdatedAt(toTs(updatedRow.updatedAt))
                .build();
    }

    @Override
    @Transactional
    public CancelEventResponse cancel(CancelEventRequest req, String clientId) {
        var clientUuid = parseClientId(clientId);
        var eventId = parseUuid(req.getEventId(), "event_id");
        var now = Instant.now();

        var sql = """
                update notification_event
                set status = 'EVENT_STATUS_CANCELLED',
                    cancelled_at = :ts,
                    cancel_reason = :reason,
                    updated_at = :ts
                where event_id = :event_id and client_id = :client_id
                  and status in ('EVENT_STATUS_DRAFT','EVENT_STATUS_READY','EVENT_STATUS_SCHEDULED')
                """;

        int updated = jdbc.update(sql, Map.of(
                "ts", now,
                "reason", req.getReason(),
                "event_id", eventId,
                "client_id", clientUuid
        ));

        if (updated == 0) {
            // если события нет NOT_FOUND
            // если статус не тот FAILED_PRECONDITION
            var exists = findEventByIdAndClient(eventId, clientUuid);
            if (exists.isEmpty()) {
                throw Status.NOT_FOUND.withDescription("event not found").asRuntimeException();
            }
            throw Status.FAILED_PRECONDITION.withDescription("cannot cancel in status: " + exists.get().status).asRuntimeException();
        }

        return CancelEventResponse.newBuilder()
                .setEventId(eventId.toString())
                .setStatus(EventStatus.EVENT_STATUS_CANCELLED)
                .setCancelledAt(toTs(now))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public GetEventResponse getEvent(GetEventRequest req, String clientId) {
        var clientUuid = parseClientId(clientId);
        var eventId = parseUuid(req.getEventId(), "event_id");

        var row = findEventByIdAndClient(eventId, clientUuid)
                .orElseThrow(() -> Status.NOT_FOUND.withDescription("event not found").asRuntimeException());

        return GetEventResponse.newBuilder()
                .setEvent(toEventView(row))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public ListEventsResponse listEvents(ListEventsRequest req, String clientId) {
        var clientUuid = parseClientId(clientId);

        int page = req.getPage();
        int size = req.getSize();
        int offset = page * size;

        var status = req.getStatusFilter().name();

        var where = new StringBuilder(" where client_id = :client_id ");
        var params = new HashMap<String, Object>();
        params.put("client_id", clientUuid);

        if (req.getStatusFilter() != EventStatus.EVENT_STATUS_UNSPECIFIED) {
            where.append(" and status = :status ");
            params.put("status", status);
        }

        long total = jdbc.queryForObject("select count(*) from notification_event" + where, params, Long.class);

        var sql = """
                select * from notification_event
                """ + where + """
                order by created_at desc
                limit :limit offset :offset
                """;
        params.put("limit", size);
        params.put("offset", offset);

        var rows = jdbc.query(sql, params, EVENT_ROW_MAPPER);

        var resp = ListEventsResponse.newBuilder()
                .setTotal(total)
                .setPage(page)
                .setSize(size);

        for (var r : rows) resp.addEvents(toEventView(r));
        return resp.build();
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Audience
    // ──────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SetAudienceResponse setAudience(SetAudienceRequest req, String clientId) {
        var clientUuid = parseClientId(clientId);
        var eventId = parseUuid(req.getEventId(), "event_id");

        var ev = findEventByIdAndClient(eventId, clientUuid)
                .orElseThrow(() -> Status.NOT_FOUND.withDescription("event not found").asRuntimeException());

        if (!isMutableStatus(ev.status)) {
            throw Status.FAILED_PRECONDITION.withDescription("event status is not mutable: " + ev.status).asRuntimeException();
        }

        upsertAudience(eventId, req.getAudience());

        // для EXPLICIT: если передали recipient_id прямо в Audience, заменим список
        if (req.getAudience().getKind() == AudienceKind.AUDIENCE_KIND_EXPLICIT
                && req.getAudience().getRecipientIdCount() > 0) {
            replaceRecipients(eventId, req.getAudience().getRecipientIdList());
        } else if (req.getAudience().getKind() != AudienceKind.AUDIENCE_KIND_EXPLICIT) {
            jdbc.update("delete from event_recipient where event_id = :event_id", Map.of("event_id", eventId));
        }

        var now = Instant.now();
        jdbc.update("update notification_event set updated_at = :ts where event_id = :event_id",
                Map.of("ts", now, "event_id", eventId));

        var updated = findEventByIdAndClient(eventId, clientUuid).orElseThrow();
        return SetAudienceResponse.newBuilder()
                .setEventId(eventId.toString())
                .setStatus(EventStatus.valueOf(updated.status))
                .setUpdatedAt(toTs(now))
                .build();
    }

    @Override
    @Transactional
    public AddRecipientsResponse addRecipients(AddRecipientsRequest req, String clientId) {
        var clientUuid = parseClientId(clientId);
        var eventId = parseUuid(req.getEventId(), "event_id");

        var ev = findEventByIdAndClient(eventId, clientUuid)
                .orElseThrow(() -> Status.NOT_FOUND.withDescription("event not found").asRuntimeException());

        if (!isMutableStatus(ev.status)) {
            throw Status.FAILED_PRECONDITION.withDescription("event status is not mutable: " + ev.status).asRuntimeException();
        }

        var aud = getAudienceRow(eventId)
                .orElseThrow(() -> Status.FAILED_PRECONDITION.withDescription("audience not set").asRuntimeException());

        if (!aud.kind.equals(AudienceKind.AUDIENCE_KIND_EXPLICIT.name())) {
            throw Status.FAILED_PRECONDITION.withDescription("addRecipients allowed only for EXPLICIT audience").asRuntimeException();
        }

        // идемпотентность обеспечиваем за счет PK(event_id, recipient_id) ON CONFLICT DO NOTHING.
        int added = insertRecipientsIgnoreDuplicates(eventId, req.getRecipientIdList());

        return AddRecipientsResponse.newBuilder()
                .setEventId(eventId.toString())
                .setAdded(added)
                .build();
    }

    @Override
    @Transactional
    public RemoveRecipientsResponse removeRecipients(RemoveRecipientsRequest req, String clientId) {
        var clientUuid = parseClientId(clientId);
        var eventId = parseUuid(req.getEventId(), "event_id");

        var ev = findEventByIdAndClient(eventId, clientUuid)
                .orElseThrow(() -> Status.NOT_FOUND.withDescription("event not found").asRuntimeException());

        if (!isMutableStatus(ev.status)) {
            throw Status.FAILED_PRECONDITION.withDescription("event status is not mutable: " + ev.status).asRuntimeException();
        }

        var sql = "delete from event_recipient where event_id = :event_id and recipient_id = :rid";
        int removed = 0;
        for (String rid : req.getRecipientIdList()) {
            removed += jdbc.update(sql, Map.of("event_id", eventId, "rid", rid));
        }

        return RemoveRecipientsResponse.newBuilder()
                .setEventId(eventId.toString())
                .setRemoved(removed)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public GetAudienceResponse getAudience(GetAudienceRequest req, String clientId) {
        var  clientUuid = parseClientId(clientId);
        var eventId = parseUuid(req.getEventId(), "event_id");

        // ownership check
        findEventByIdAndClient(eventId, clientUuid)
                .orElseThrow(() -> Status.NOT_FOUND.withDescription("event not found").asRuntimeException());

        var aud = getAudienceRow(eventId)
                .orElseThrow(() -> Status.NOT_FOUND.withDescription("audience not found").asRuntimeException());

        var builder = Audience.newBuilder()
                .setKind(AudienceKind.valueOf(aud.kind))
                .setSnapshotOnDispatch(aud.snapshotOnDispatch);

        if (aud.kind.equals(AudienceKind.AUDIENCE_KIND_EXPLICIT.name())) {
            var rec = jdbc.queryForList(
                    "select recipient_id from event_recipient where event_id = :event_id order by recipient_id",
                    Map.of("event_id", eventId),
                    String.class
            );
            builder.addAllRecipientId(rec);
        }

        if (aud.segmentId != null) {
            builder.setSegmentId(aud.segmentId);
        }

        return GetAudienceResponse.newBuilder()
                .setEventId(eventId.toString())
                .setAudience(builder.build())
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Dispatch
    // ──────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public TriggerDispatchResponse triggerDispatch(TriggerDispatchRequest req, String clientId) {
        var clientUuid = parseClientId(clientId);
        var eventId = parseUuid(req.getEventId(), "event_id");

        var ev = findEventByIdAndClient(eventId, clientUuid)
                .orElseThrow(() -> Status.NOT_FOUND.withDescription("event not found").asRuntimeException());

        if (ev.status.equals(EventStatus.EVENT_STATUS_CANCELLED.name())) {
            throw Status.FAILED_PRECONDITION.withDescription("event is cancelled").asRuntimeException();
        }

        var aud = getAudienceRow(eventId)
                .orElseThrow(() -> Status.FAILED_PRECONDITION.withDescription("audience not set").asRuntimeException());

        var planned = req.hasOverrideSendAt()
                ? OffsetDateTime.ofInstant(toInstant(req.getOverrideSendAt()), ZoneOffset.UTC)
                : (ev.scheduledAt != null
                ? OffsetDateTime.ofInstant(ev.scheduledAt, ZoneOffset.UTC)
                : OffsetDateTime.now(ZoneOffset.UTC));

        // idempotent dispatch by (event_id, idempotency_key)
        var dispatchId = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);

        try {
            jdbc.update("""
                    insert into dispatch(dispatch_id, event_id, idempotency_key, status, planned_send_at, total_targets, enqueued, created_at)
                    values(:dispatch_id, :event_id, :idem, 'DISPATCH_STATUS_PENDING', :planned, 0, 0, :created_at)
                    """, Map.of(
                    "dispatch_id", dispatchId,
                    "event_id", eventId,
                    "idem", req.getIdempotencyKey(),
                    "planned", planned,
                    "created_at", now
            ));
        } catch (Exception e) {
            var existed = jdbc.query("""
                            select dispatch_id, status from dispatch where event_id = :event_id and idempotency_key = :idem
                            """, Map.of("event_id", eventId, "idem", req.getIdempotencyKey()),
                    (rs) -> rs.next() ? Map.entry(UUID.fromString(rs.getString("dispatch_id")), rs.getString("status")) : null
            );
            if (existed != null) {
                return TriggerDispatchResponse.newBuilder()
                        .setDispatchId(existed.getKey().toString())
                        .setStatus(DispatchStatus.valueOf(existed.getValue()))
                        .build();
            }
            throw Status.INTERNAL.withDescription("Failed to create dispatch").withCause(e).asRuntimeException();
        }

        // snapshot targets (только explicit для MVP)
        if (aud.snapshotOnDispatch) {
            if (!aud.kind.equals(AudienceKind.AUDIENCE_KIND_EXPLICIT.name())) {
                throw Status.UNIMPLEMENTED.withDescription("snapshot for audience kind not implemented: " + aud.kind).asRuntimeException();
            }
            var recipients = jdbc.queryForList(
                    "select recipient_id from event_recipient where event_id = :event_id",
                    Map.of("event_id", eventId),
                    String.class
            );
            insertDispatchTargets(dispatchId, recipients);

            jdbc.update("""
                            update dispatch set total_targets = :total
                            where dispatch_id = :dispatch_id
                            """,
                    Map.of("total", (long) recipients.size(), "dispatch_id", dispatchId)
            );

            enqueueMailDispatch(dispatchId, ev, recipients, planned, now);
        }

        jdbc.update("""
                update notification_event set status = 'EVENT_STATUS_DISPATCHING', updated_at = :ts
                where event_id = :event_id and client_id = :client_id
                """, Map.of("ts", now, "event_id", eventId, "client_id", clientUuid));

        return TriggerDispatchResponse.newBuilder()
                .setDispatchId(dispatchId.toString())
                .setStatus(DispatchStatus.DISPATCH_STATUS_PENDING)
                .build();
    }

    private void enqueueMailDispatch(UUID dispatchId,
                                     EventRow event,
                                     List<String> recipients,
                                     OffsetDateTime plannedAt,
                                     OffsetDateTime createdAt) {
        var payload = Map.<String, Object>of(
                "dispatch_id", dispatchId.toString(),
                "event_id", event.eventId.toString(),
                "client_id", event.clientId.toString(),
                "template_id", event.templateId,
                "template_version", event.templateVersion,
                "preferred_channel", event.preferredChannel,
                "payload", readJsonMap(event.payloadJson),
                "recipient_ids", recipients,
                "planned_send_at", plannedAt == null ? null : plannedAt.toString(),
                "created_at", createdAt.toString()
        );

        var headers = Map.<String, Object>of(
                "message_id", dispatchId.toString(),
                "event_type", "MailDispatchRequested"
        );

        jdbc.update("""
                insert into nf_fac.outbox_message(
                  aggregate_type, aggregate_id, event_type, payload, headers, created_at
                ) values (
                  :aggregate_type, :aggregate_id, :event_type, cast(:payload as jsonb), cast(:headers as jsonb), :created_at
                )
                """, new MapSqlParameterSource()
                .addValue("aggregate_type", "mail_dispatch")
                .addValue("aggregate_id", dispatchId.toString())
                .addValue("event_type", "MailDispatchRequested")
                .addValue("payload", toJson(payload))
                .addValue("headers", toJson(headers))
                .addValue("created_at", createdAt));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(String json) {
        try {
            return json == null || json.isBlank()
                    ? Map.of()
                    : objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw Status.INTERNAL.withDescription("Failed to serialize payload").withCause(e).asRuntimeException();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public GetDispatchResponse getDispatch(GetDispatchRequest req, String clientId) {
        var clientUuid = parseClientId(clientId);
        var dispatchId = parseUuid(req.getDispatchId(), "dispatch_id");

        // join to verify client ownership via event
        var sql = """
                select d.*, e.client_id
                from dispatch d
                join notification_event e on e.event_id = d.event_id
                where d.dispatch_id = :dispatch_id
                """;

        var rows = jdbc.query(sql, Map.of("dispatch_id", dispatchId), DISPATCH_ROW_MAPPER);
        if (rows.isEmpty()) throw Status.NOT_FOUND.withDescription("dispatch not found").asRuntimeException();

        var d = rows.get(0);
        if (!d.clientId.equals(clientUuid))
            throw Status.NOT_FOUND.withDescription("dispatch not found").asRuntimeException();

        return GetDispatchResponse.newBuilder()
                .setDispatch(toDispatchView(d))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public ListDispatchesResponse listDispatches(ListDispatchesRequest req, String clientId) {
        UUID clientUuid = parseClientId(clientId);
        UUID eventId = parseUuid(req.getEventId(), "event_id");

        // ensure event belongs to client
        findEventByIdAndClient(eventId, clientUuid)
                .orElseThrow(() -> Status.NOT_FOUND.withDescription("event not found").asRuntimeException());

        int page = req.getPage();
        int size = req.getSize();
        int offset = page * size;

        long total = jdbc.queryForObject(
                "select count(*) from dispatch where event_id = :event_id",
                Map.of("event_id", eventId),
                Long.class
        );

        var sql = """
                select d.*, e.client_id
                from dispatch d
                join notification_event e on e.event_id = d.event_id
                where d.event_id = :event_id
                order by d.created_at desc
                limit :limit offset :offset
                """;

        var params = new HashMap<String, Object>();
        params.put("event_id", eventId);
        params.put("limit", size);
        params.put("offset", offset);

        var rows = jdbc.query(sql, params, DISPATCH_ROW_MAPPER);

        var resp = ListDispatchesResponse.newBuilder()
                .setTotal(total)
                .setPage(page)
                .setSize(size);

        for (var r : rows) {
            resp.addDispatches(toDispatchView(r));
        }
        return resp.build();
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Helpers: Audience persistence
    // ──────────────────────────────────────────────────────────────────────────────

    private void upsertAudience(UUID eventId, Audience a) {
        var sql = """
                insert into event_audience(event_id, kind, snapshot_on_dispatch, segment_id)
                values(:event_id, :kind, :snapshot, :segment_id)
                on conflict (event_id) do update set
                  kind = excluded.kind,
                  snapshot_on_dispatch = excluded.snapshot_on_dispatch,
                  segment_id = excluded.segment_id
                """;

        var params = new MapSqlParameterSource()
                .addValue("event_id", eventId)
                .addValue("kind", a.getKind().name())
                .addValue("snapshot", a.getSnapshotOnDispatch())
                .addValue("segment_id", a.getSegmentId().isBlank() ? null : a.getSegmentId());
        jdbc.update(sql, params);
    }

    private void replaceRecipients(UUID eventId, List<String> recipients) {
        jdbc.update("delete from event_recipient where event_id = :event_id", Map.of("event_id", eventId));
        insertRecipientsIgnoreDuplicates(eventId, recipients);
    }

    private int insertRecipientsIgnoreDuplicates(UUID eventId, List<String> recipients) {
        // batch insert with named params
        var sql = """
                insert into event_recipient(event_id, recipient_id)
                values(:event_id, :recipient_id)
                on conflict do nothing
                """;

        var batch = recipients.stream()
                .map(rid -> new MapSqlParameterSource()
                        .addValue("event_id", eventId)
                        .addValue("recipient_id", rid))
                .toArray(SqlParameterSource[]::new);

        int[] res = jdbc.batchUpdate(sql, batch);
        // Postgres returns 1 for inserted, 0 for do nothing
        int added = 0;
        for (int r : res) {
            added += r;
        }
        return added;
    }

    private void insertDispatchTargets(UUID dispatchId, List<String> recipients) {
        var sql = """
                insert into dispatch_target(dispatch_id, recipient_id)
                values(:dispatch_id, :recipient_id)
                on conflict do nothing
                """;

        var batch = recipients.stream()
                .map(rid -> new MapSqlParameterSource()
                        .addValue("dispatch_id", dispatchId)
                        .addValue("recipient_id", rid))
                .toArray(SqlParameterSource[]::new);

        jdbc.batchUpdate(sql, batch);
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Row mappers + finders
    // ──────────────────────────────────────────────────────────────────────────────

    private Optional<EventRow> findEventByClientAndIdem(UUID clientId, String idemKey) {
        var rows = jdbc.query("""
                select * from notification_event
                where client_id = :client_id and idempotency_key = :idem
                """, Map.of("client_id", clientId, "idem", idemKey), EVENT_ROW_MAPPER);
        return rows.stream().findFirst();
    }

    private Optional<EventRow> findEventByIdAndClient(UUID eventId, UUID clientId) {
        var rows = jdbc.query("""
                select * from notification_event
                where event_id = :event_id and client_id = :client_id
                """, Map.of("event_id", eventId, "client_id", clientId), EVENT_ROW_MAPPER);
        return rows.stream().findFirst();
    }

    private Optional<AudienceRow> getAudienceRow(UUID eventId) {
        var rows = jdbc.query("""
                select event_id, kind, snapshot_on_dispatch, segment_id
                from event_audience
                where event_id = :event_id
                """, Map.of("event_id", eventId), AUDIENCE_ROW_MAPPER);
        return rows.stream().findFirst();
    }

    private static boolean isMutableStatus(String status) {
        return status.equals(EventStatus.EVENT_STATUS_DRAFT.name())
                || status.equals(EventStatus.EVENT_STATUS_READY.name())
                || status.equals(EventStatus.EVENT_STATUS_SCHEDULED.name());
    }

    private EventView toEventView(EventRow eventRow) {
        var b = EventView.newBuilder()
                .setEventId(eventRow.eventId.toString())
                .setIdempotencyKey(eventRow.idempotencyKey)
                .setClientId(eventRow.clientId.toString())
                .setTemplateId(eventRow.templateId)
                .setTemplateVersion(eventRow.templateVersion)
                .setPriority(DeliveryPriority.valueOf(eventRow.priority))
                .setPreferredChannel(Channel.valueOf(eventRow.preferredChannel))
                .setStatus(EventStatus.valueOf(eventRow.status))
                .setCreatedAt(toTs(eventRow.createdAt))
                .setUpdatedAt(toTs(eventRow.updatedAt));

        if (eventRow.cancelledAt != null) {
            b.setCancelledAt(toTs(eventRow.cancelledAt));
        }
        if (eventRow.cancelReason != null) {
            b.setCancelReason(eventRow.cancelReason);
        }

        // strategy
        var sb = DeliveryStrategy.newBuilder()
                .setKind(StrategyKind.valueOf(eventRow.strategyKind));
        if (eventRow.scheduledAt != null) {
            sb.setSendAt(toTs(eventRow.scheduledAt));
        }
        b.setStrategy(sb.build());

        // payload json -> map
        try {
            @SuppressWarnings("unchecked")
           var m = (Map<String, String>) objectMapper.readValue(eventRow.payloadJson, Map.class);
            b.putAllPayload(m);
        } catch (Exception e) {
            LOG.warn("could not create event view: {}", e.getMessage(), e);
        }

        return b.build();
    }

    private DispatchView toDispatchView(DispatchRow r) {
        var b = DispatchView.newBuilder()
                .setDispatchId(r.dispatchId.toString())
                .setEventId(r.eventId.toString())
                .setStatus(DispatchStatus.valueOf(r.status))
                .setTotalTargets(r.totalTargets)
                .setEnqueued(r.enqueued);

        if (r.plannedSendAt != null) {
            b.setPlannedSendAt(toTs(r.plannedSendAt));
        }
        if (r.startedAt != null) {
            b.setStartedAt(toTs(r.startedAt));
        }
        if (r.finishedAt != null) {
            b.setFinishedAt(toTs(r.finishedAt));
        }
        return b.build();
    }

    private Map<String, String> resolveDeliveryPayload(String clientId,
                                                       String templateId,
                                                       int templateVersion,
                                                       Channel preferredChannel,
                                                       Map<String, String> payload) {
        var resolved = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        if (hasInlineTemplate(resolved)) {
            LOG.debug("Using inline template payload, skip template registry render. clientId={}, templateId={}",
                    clientId, templateId);
            return resolved;
        }
        if (!templateRegistryRenderClient.isEnabled()) {
            LOG.debug("Template registry integration disabled. Keep payload as-is. clientId={}, templateId={}",
                    clientId, templateId);
            return resolved;
        }
        if (templateId == null || templateId.isBlank() || preferredChannel == Channel.CHANNEL_UNSPECIFIED) {
            LOG.debug("Template registry render skipped because templateId/channel is not set. clientId={}, templateId={}, channel={}",
                    clientId, templateId, preferredChannel);
            return resolved;
        }

        LOG.info("Rendering template via template-registry. clientId={}, templateId={}, templateVersion={}, channel={}",
                clientId, templateId, templateVersion, preferredChannel);
        var rendered = templateRegistryRenderClient.renderPreview(
                templateId,
                templateVersion,
                preferredChannel,
                resolved
        );
        resolved.put("subject", rendered.subject());
        resolved.put("body", rendered.body());
        LOG.debug("Template rendered and injected into payload. clientId={}, templateId={}, payloadSize={}",
                clientId, templateId, resolved.size());
        return resolved;
    }

    private Map<String, String> readPayloadMap(String payloadJson) {
        try {
            @SuppressWarnings("unchecked")
            var raw = (Map<String, Object>) objectMapper.readValue(payloadJson, Map.class);
            var result = new LinkedHashMap<String, String>();
            raw.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
            return result;
        } catch (Exception e) {
            LOG.warn("Failed to parse stored payload json, err={}", e.getMessage(), e);
            throw Status.INTERNAL.withDescription("Failed to parse stored payload").withCause(e).asRuntimeException();
        }
    }

    private boolean hasInlineTemplate(Map<String, String> payload) {
        return hasText(payload.get("subject"))
                && (hasText(payload.get("body"))
                || hasText(payload.get("text"))
                || hasText(payload.get("message"))
                || hasText(payload.get("content")));
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String toJson(Map<String, ?> map) {
        try {
            return objectMapper.writeValueAsString(map == null ? Map.of() : map);
        } catch (JsonProcessingException e) {
            throw Status.INVALID_ARGUMENT.withDescription("payload is not serializable").withCause(e).asRuntimeException();
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }

    private static Timestamp toTs(Instant i) {
        return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
    }

    private static UUID parseClientId(String clientId) {
        try {
            return UUID.fromString(clientId);
        } catch (Exception e) {
            throw Status.UNAUTHENTICATED.withDescription("invalid client_id").asRuntimeException();
        }
    }

    private static UUID parseUuid(String v, String field) {
        try {
            return UUID.fromString(v);
        } catch (Exception e) {
            throw Status.INVALID_ARGUMENT.withDescription(field + " must be UUID").asRuntimeException();
        }
    }

    private static final RowMapper<EventRow> EVENT_ROW_MAPPER = (rs, rowNum) -> new EventRow(rs);
    private static final RowMapper<AudienceRow> AUDIENCE_ROW_MAPPER = (rs, rowNum) -> new AudienceRow(rs);
    private static final RowMapper<DispatchRow> DISPATCH_ROW_MAPPER = (rs, rowNum) -> new DispatchRow(rs);

    private static Instant readInstant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static final class EventRow {
        final UUID eventId;
        final UUID clientId;
        final String idempotencyKey;
        final String templateId;
        final int templateVersion;
        final String priority;
        final String preferredChannel;
        final String strategyKind;
        final Instant scheduledAt;
        final String status;
        final String payloadJson;
        final Instant createdAt;
        final Instant updatedAt;
        final Instant cancelledAt;
        final String cancelReason;

        EventRow(ResultSet rs) throws SQLException {
            this.eventId = UUID.fromString(rs.getString("event_id"));
            this.clientId = UUID.fromString(rs.getString("client_id"));
            this.idempotencyKey = rs.getString("idempotency_key");
            this.templateId = rs.getString("template_id");
            this.templateVersion = rs.getInt("template_version");
            this.priority = rs.getString("priority");
            this.preferredChannel = rs.getString("preferred_channel");
            this.strategyKind = rs.getString("strategy_kind");
            this.scheduledAt = readInstant(rs, "scheduled_at");
            this.status = rs.getString("status");
            this.payloadJson = rs.getString("payload");
            this.createdAt = readInstant(rs, "created_at");
            this.updatedAt = readInstant(rs, "updated_at");
            this.cancelledAt = readInstant(rs, "cancelled_at");
            this.cancelReason = rs.getString("cancel_reason");
        }
    }

    private static final class AudienceRow {
        final UUID eventId;
        final String kind;
        final boolean snapshotOnDispatch;
        final String segmentId;

        AudienceRow(ResultSet rs) throws SQLException {
            this.eventId = UUID.fromString(rs.getString("event_id"));
            this.kind = rs.getString("kind");
            this.snapshotOnDispatch = rs.getBoolean("snapshot_on_dispatch");
            this.segmentId = rs.getString("segment_id");
        }
    }

    private static final class DispatchRow {
        final UUID dispatchId;
        final UUID eventId;
        final UUID clientId;
        final String status;
        final Instant plannedSendAt;
        final Instant startedAt;
        final Instant finishedAt;
        final long totalTargets;
        final long enqueued;

        DispatchRow(ResultSet rs) throws SQLException {
            this.dispatchId = UUID.fromString(rs.getString("dispatch_id"));
            this.eventId = UUID.fromString(rs.getString("event_id"));
            this.clientId = UUID.fromString(rs.getString("client_id"));
            this.status = rs.getString("status");
            this.plannedSendAt = readInstant(rs, "planned_send_at");
            this.startedAt = readInstant(rs, "started_at");
            this.finishedAt = readInstant(rs, "finished_at");
            this.totalTargets = rs.getLong("total_targets");
            this.enqueued = rs.getLong("enqueued");
        }
    }
}
