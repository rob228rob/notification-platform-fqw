package ru.batoyan.vkr.notification.facade.helpers;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.FieldMask;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.notification.common.proto.v1.DeliveryPriority;
import ru.notification.facade.proto.v1.Audience;
import ru.notification.facade.proto.v1.AudienceKind;
import ru.notification.facade.proto.v1.CreateEventRequest;
import ru.notification.facade.proto.v1.DeliveryStrategy;
import ru.notification.facade.proto.v1.StrategyKind;
import ru.notification.facade.proto.v1.UpdateEventRequest;

class ValidationsTest {

    @Test
    void shouldAcceptCreateCommandWithTemplateAndImmediateStrategy() {
        var request = validCreateRequest().build();

        assertThatCode(() -> Validations.validateCreate(request)).doesNotThrowAnyException();
    }

    @Test
    void shouldAcceptCreateCommandWithInlineTemplatePayload() {
        var request = validCreateRequest()
                .clearTemplateId()
                .putPayload("subject", "Hello")
                .putPayload("body", "Body")
                .build();

        assertThatCode(() -> Validations.validateCreate(request)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectCreateCommandWithoutIdempotencyKey() {
        var request = validCreateRequest().clearIdempotencyKey().build();

        assertInvalidArgument(() -> Validations.validateCreate(request));
    }

    @Test
    void shouldRejectCreateCommandWithoutTemplateAndInlinePayload() {
        var request = validCreateRequest().clearTemplateId().build();

        assertInvalidArgument(() -> Validations.validateCreate(request));
    }

    @Test
    void shouldRejectScheduledCreateCommandWithoutSendAt() {
        var request = validCreateRequest()
                .setStrategy(DeliveryStrategy.newBuilder()
                        .setKind(StrategyKind.STRATEGY_KIND_SCHEDULED)
                        .build())
                .build();

        assertInvalidArgument(() -> Validations.validateCreate(request));
    }

    @Test
    void shouldRejectGroupAudienceWithoutGroups() {
        var audience = Audience.newBuilder()
                .setKind(AudienceKind.AUDIENCE_KIND_GROUPS)
                .build();

        assertInvalidArgument(() -> Validations.validateAudience(audience));
    }

    @Test
    void shouldRejectForbiddenUpdateMaskPath() {
        var request = UpdateEventRequest.newBuilder()
                .setEventId("00000000-0000-0000-0000-000000000001")
                .setUpdateMask(FieldMask.newBuilder().addPaths("client_id"))
                .build();

        assertInvalidArgument(() -> Validations.validateUpdate(request));
    }

    @Test
    void shouldRejectInvalidUuid() {
        assertInvalidArgument(() -> Validations.requireUuid("not-a-uuid", "event_id"));
    }

    @Test
    void shouldRejectInvalidPagination() {
        assertInvalidArgument(() -> Validations.validateList(0, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "body",
            "text",
            "message",
            "content"
    })
    void shouldAcceptInlineTemplatePayloadWhenSubjectAndContentFieldArePresent(String contentField) {
        var request = validCreateRequest()
                .clearTemplateId()
                .putPayload("subject", "Hello")
                .putPayload(contentField, "Rendered content")
                .build();

        assertThatCode(() -> Validations.validateCreate(request)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "not-a-uuid",
            "00000000-0000-0000-0000-00000000000x",
            "00000000-0000-0000-0000-000000000001-extra",
            "event-1",
            "123",
            "null",
            "ffffffff-ffff-ffff-ffff-fffffffffffz"
    })
    void shouldRejectInvalidUuidValues(String value) {
        assertInvalidArgument(() -> Validations.requireUuid(value, "event_id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "00000000-0000-0000-0000-000000000001",
            "11111111-1111-1111-1111-111111111111",
            "ffffffff-ffff-ffff-ffff-ffffffffffff",
            "123e4567-e89b-12d3-a456-426614174000",
            "9f6b30ef-6a28-49d8-9e60-cbe5b6a7d777"
    })
    void shouldAcceptValidUuidValues(String value) {
        assertThatCode(() -> Validations.requireUuid(value, "event_id")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({
            "0,1,true",
            "0,10,true",
            "1,50,true",
            "10,100,true",
            "-1,10,false",
            "-10,10,false",
            "0,0,false",
            "0,-1,false",
            "0,101,false",
            "5,1000,false"
    })
    void shouldValidatePaginationBoundaries(int page, int size, boolean valid) {
        if (valid) {
            assertThatCode(() -> Validations.validateList(page, size)).doesNotThrowAnyException();
            return;
        }
        assertInvalidArgument(() -> Validations.validateList(page, size));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "client_id",
            "event_id",
            "created_at",
            "updated_at",
            "status",
            "dispatches",
            "audience",
            "outbox",
            "cancel_reason",
            "unknown",
            "payload.subject",
            "strategy.send_at"
    })
    void shouldRejectForbiddenUpdateMaskPaths(String path) {
        var request = UpdateEventRequest.newBuilder()
                .setEventId("00000000-0000-0000-0000-000000000001")
                .setUpdateMask(FieldMask.newBuilder().addPaths(path))
                .build();

        assertInvalidArgument(() -> Validations.validateUpdate(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "payload",
            "template_version",
            "priority",
            "strategy",
            "preferred_channel"
    })
    void shouldAcceptAllowedUpdateMaskPaths(String path) {
        var builder = UpdateEventRequest.newBuilder()
                .setEventId("00000000-0000-0000-0000-000000000001")
                .setUpdateMask(FieldMask.newBuilder().addPaths(path));
        if ("strategy".equals(path)) {
            builder.setStrategy(DeliveryStrategy.newBuilder()
                    .setKind(StrategyKind.STRATEGY_KIND_IMMEDIATE)
                    .build());
        }

        assertThatCode(() -> Validations.validateUpdate(builder.build())).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({
            "AUDIENCE_KIND_EXPLICIT,,true",
            "AUDIENCE_KIND_GROUPS,group-1,true",
            "AUDIENCE_KIND_GROUPS,,false",
            "AUDIENCE_KIND_SEGMENT,segment-1,true",
            "AUDIENCE_KIND_SEGMENT,,false",
            "AUDIENCE_KIND_UNSPECIFIED,,false"
    })
    void shouldValidateAudienceKinds(AudienceKind kind, String identifier, boolean valid) {
        var builder = Audience.newBuilder().setKind(kind);
        if (kind == AudienceKind.AUDIENCE_KIND_GROUPS && identifier != null) {
            builder.addGroupId(identifier);
        }
        if (kind == AudienceKind.AUDIENCE_KIND_SEGMENT && identifier != null) {
            builder.setSegmentId(identifier);
        }

        if (valid) {
            assertThatCode(() -> Validations.validateAudience(builder.build())).doesNotThrowAnyException();
            return;
        }
        assertInvalidArgument(() -> Validations.validateAudience(builder.build()));
    }

    @ParameterizedTest
    @CsvSource({
            "DELIVERY_PRIORITY_LOW,STRATEGY_KIND_IMMEDIATE,true",
            "DELIVERY_PRIORITY_NORMAL,STRATEGY_KIND_IMMEDIATE,true",
            "DELIVERY_PRIORITY_HIGH,STRATEGY_KIND_IMMEDIATE,true",
            "DELIVERY_PRIORITY_LOW,STRATEGY_KIND_SCHEDULED,true",
            "DELIVERY_PRIORITY_NORMAL,STRATEGY_KIND_SCHEDULED,true",
            "DELIVERY_PRIORITY_HIGH,STRATEGY_KIND_SCHEDULED,true",
            "DELIVERY_PRIORITY_UNSPECIFIED,STRATEGY_KIND_IMMEDIATE,false",
            "DELIVERY_PRIORITY_NORMAL,STRATEGY_KIND_UNSPECIFIED,false"
    })
    void shouldValidateCreatePriorityAndStrategy(DeliveryPriority priority, StrategyKind strategyKind, boolean valid) {
        var strategy = DeliveryStrategy.newBuilder()
                .setKind(strategyKind);
        if (strategyKind == StrategyKind.STRATEGY_KIND_SCHEDULED) {
            strategy.setSendAt(Timestamp.newBuilder().setSeconds(10).build());
        }
        var request = validCreateRequest()
                .setPriority(priority)
                .setStrategy(strategy)
                .build();

        if (valid) {
            assertThatCode(() -> Validations.validateCreate(request)).doesNotThrowAnyException();
            return;
        }
        assertInvalidArgument(() -> Validations.validateCreate(request));
    }

    private static CreateEventRequest.Builder validCreateRequest() {
        return CreateEventRequest.newBuilder()
                .setIdempotencyKey("idem-1")
                .setTemplateId("template-1")
                .setTemplateVersion(1)
                .setPriority(DeliveryPriority.DELIVERY_PRIORITY_NORMAL)
                .setStrategy(DeliveryStrategy.newBuilder()
                        .setKind(StrategyKind.STRATEGY_KIND_IMMEDIATE)
                        .setSendAt(Timestamp.newBuilder().setSeconds(1).build())
                        .build());
    }

    private static void assertInvalidArgument(ThrowingRunnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
