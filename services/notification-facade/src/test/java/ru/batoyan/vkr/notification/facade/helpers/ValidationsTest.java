package ru.batoyan.vkr.notification.facade.helpers;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.FieldMask;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
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
