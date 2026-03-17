package ru.batoyan.vkr.notification.facade.helpers;

import com.google.protobuf.FieldMask;
import io.grpc.Status;
import jakarta.annotation.Nullable;
import org.springframework.util.StringUtils;
import ru.notification.common.proto.v1.DeliveryPriority;
import ru.notification.facade.proto.v1.*;

import java.util.Map;
import java.util.UUID;

import static ru.batoyan.vkr.notification.facade.constants.Constants.MAX_PAGE_SIZE;
import static ru.batoyan.vkr.notification.facade.constants.Constants.UPDATE_MASK_ALLOWED;

/**
 * @author batoyan.rl
 * @since 23.02.2026
 */
public final class Validations {

    private Validations() {
    }

    public static void validateCreate(@Nullable CreateEventRequest r) {
        if (r == null) {
            throw Status.INVALID_ARGUMENT.withDescription("request must not be null").asRuntimeException();
        }
        requireNotBlank(r.getIdempotencyKey(), "idempotency_key");
        if (!StringUtils.hasText(r.getTemplateId()) && !hasInlineTemplate(r.getPayloadMap())) {
            throw Status.INVALID_ARGUMENT
                    .withDescription("template_id must be set or inline subject/body must be provided in payload")
                    .asRuntimeException();
        }

        if (!r.hasStrategy()) {
            throw Status.INVALID_ARGUMENT.withDescription("strategy must be set").asRuntimeException();
        }
        if (r.getPriority() == DeliveryPriority.DELIVERY_PRIORITY_UNSPECIFIED) {
            throw Status.INVALID_ARGUMENT.withDescription("priority must be set").asRuntimeException();
        }
        if (r.getStrategy().getKind() == StrategyKind.STRATEGY_KIND_UNSPECIFIED) {
            throw Status.INVALID_ARGUMENT.withDescription("strategy.kind must be set").asRuntimeException();
        }
        if (r.getStrategy().getKind() == StrategyKind.STRATEGY_KIND_SCHEDULED
                && !r.getStrategy().hasSendAt()) {
            throw Status.INVALID_ARGUMENT.withDescription("strategy.send_at must be set for SCHEDULED").asRuntimeException();
        }
        if (r.hasAudience()) {
            validateAudience(r.getAudience());
        }
    }

    public static void validateUpdate(@Nullable UpdateEventRequest r) {
        if (r == null) {
            throw Status.INVALID_ARGUMENT.withDescription("request must not be null").asRuntimeException();
        }
        requireUuid(r.getEventId(), "event_id");
        validateUpdateMask(r.getUpdateMask());

        // Если маска содержит payload — payload может быть пустой map
        // Если маска содержит strategy — kind обязателен
        if (r.getUpdateMask().getPathsList().contains("strategy")) {
            if (!r.hasStrategy()) {
                throw Status.INVALID_ARGUMENT.withDescription("strategy must be set when updating strategy").asRuntimeException();
            }
            if (r.getStrategy().getKind() == StrategyKind.STRATEGY_KIND_UNSPECIFIED) {
                throw Status.INVALID_ARGUMENT.withDescription("strategy.kind must be set when updating strategy").asRuntimeException();
            }
            if (r.getStrategy().getKind() == StrategyKind.STRATEGY_KIND_SCHEDULED
                    && !r.getStrategy().hasSendAt()) {
                throw Status.INVALID_ARGUMENT.withDescription("strategy.send_at must be set for SCHEDULED").asRuntimeException();
            }
        }
    }

    public static void validateAudience(@Nullable Audience a) {
        if (a == null) {
            throw Status.INVALID_ARGUMENT.withDescription("audience must not be null").asRuntimeException();
        }
        if (a.getKind() == AudienceKind.AUDIENCE_KIND_UNSPECIFIED) {
            throw Status.INVALID_ARGUMENT.withDescription("audience.kind must be set").asRuntimeException();
        }
        switch (a.getKind()) {
            case AUDIENCE_KIND_EXPLICIT -> {
                // допускаем, что explicit может быть пустым, если добавляют через AddRecipients батчами
            }
            case AUDIENCE_KIND_GROUPS -> {
                if (a.getGroupIdCount() == 0) {
                    throw Status.INVALID_ARGUMENT.withDescription("audience.group_id must not be empty for GROUPS").asRuntimeException();
                }
            }
            case AUDIENCE_KIND_SEGMENT -> {
                requireNotBlank(a.getSegmentId(), "audience.segment_id");
            }
            default -> throw Status.INVALID_ARGUMENT.withDescription("unsupported audience.kind").asRuntimeException();
        }
    }

    public static void validateUpdateMask(@Nullable FieldMask mask) {
        if (mask == null || mask.getPathsCount() == 0) {
            throw Status.INVALID_ARGUMENT.withDescription("update_mask.paths must not be empty").asRuntimeException();
        }

        for (String p : mask.getPathsList()) {
            if (!UPDATE_MASK_ALLOWED.contains(p)) {
                throw Status.INVALID_ARGUMENT.withDescription("update_mask contains forbidden path: " + p).asRuntimeException();
            }
        }
    }

    public static void validateList(int page, int size) {
        if (page < 0) {
            throw Status.INVALID_ARGUMENT.withDescription("page must be >= 0").asRuntimeException();
        }
        if (size <= 0 || size > MAX_PAGE_SIZE) {
            throw Status.INVALID_ARGUMENT.withDescription("size must be 1.." + MAX_PAGE_SIZE).asRuntimeException();
        }
    }

    public static void requireNotBlank(String v, String field) {
        if (!StringUtils.hasText(v)) {
            throw Status.INVALID_ARGUMENT.withDescription(field + " must not be blank").asRuntimeException();
        }
    }

    private static boolean hasInlineTemplate(Map<String, String> payload) {
        return hasText(payload.get("subject"))
                && (hasText(payload.get("body"))
                || hasText(payload.get("text"))
                || hasText(payload.get("message"))
                || hasText(payload.get("content")));
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.trim().isEmpty();
    }

    public  static void requireUuid(String v, String field) {
        requireNotBlank(v, field);
        try {
            UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            throw Status.INVALID_ARGUMENT.withDescription(field + " must be UUID").asRuntimeException();
        }
    }
}
