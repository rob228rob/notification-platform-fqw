package ru.batoyan.vkr.notification.profile.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.batoyan.vkr.notification.profile.model.ChannelConsent;
import ru.batoyan.vkr.notification.profile.model.RecipientProfileDomain;
import ru.batoyan.vkr.notification.profile.service.RecipientProfileService;
import ru.notification.common.proto.v1.Channel;
import ru.notification.profile.proto.v1.BatchGetRecipientProfilesRequest;
import ru.notification.profile.proto.v1.BatchGetRecipientProfilesResponse;
import ru.notification.profile.proto.v1.CHECK_REASON_CODE;
import ru.notification.profile.proto.v1.CheckRecipientChannelRequest;
import ru.notification.profile.proto.v1.CheckRecipientChannelResponse;
import ru.notification.profile.proto.v1.GetRecipientProfileRequest;
import ru.notification.profile.proto.v1.GetRecipientProfileResponse;

class ProfileConsentGrpcServiceTest {

    @Test
    void getRecipientProfileShouldReturnProfile() {
        var service = mock(RecipientProfileService.class);
        when(service.getRecipientProfile("recipient-1", "tenant-a")).thenReturn(Optional.of(profile()));
        var observer = new TestObserver<GetRecipientProfileResponse>();

        new ProfileConsentGrpcService(service).getRecipientProfile(GetRecipientProfileRequest.newBuilder()
                .setRecipientId("recipient-1")
                .setTenant("tenant-a")
                .build(), observer);

        assertThat(observer.completed).isTrue();
        assertThat(observer.value.getProfile().getRecipientId()).isEqualTo("recipient-1");
        assertThat(observer.value.getProfile().getChannelsCount()).isEqualTo(1);
    }

    @Test
    void getRecipientProfileShouldReturnNotFound() {
        var service = mock(RecipientProfileService.class);
        when(service.getRecipientProfile("missing", "tenant-a")).thenReturn(Optional.empty());
        var observer = new TestObserver<GetRecipientProfileResponse>();

        new ProfileConsentGrpcService(service).getRecipientProfile(GetRecipientProfileRequest.newBuilder()
                .setRecipientId("missing")
                .setTenant("tenant-a")
                .build(), observer);

        assertThat(status(observer.error)).isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void getRecipientProfileShouldRejectBlankRecipientId() {
        var observer = new TestObserver<GetRecipientProfileResponse>();

        new ProfileConsentGrpcService(mock(RecipientProfileService.class))
                .getRecipientProfile(GetRecipientProfileRequest.newBuilder().build(), observer);

        assertThat(status(observer.error)).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void batchGetRecipientProfilesShouldReturnFoundProfiles() {
        var service = mock(RecipientProfileService.class);
        when(service.getRecipientProfiles(java.util.List.of("recipient-1"), "tenant-a"))
                .thenReturn(Map.of("recipient-1", profile()));
        var observer = new TestObserver<BatchGetRecipientProfilesResponse>();

        new ProfileConsentGrpcService(service).batchGetRecipientProfiles(BatchGetRecipientProfilesRequest.newBuilder()
                .addRecipientId("recipient-1")
                .setTenant("tenant-a")
                .build(), observer);

        assertThat(observer.value.getProfilesCount()).isEqualTo(1);
        verify(service).getRecipientProfiles(java.util.List.of("recipient-1"), "tenant-a");
    }

    @Test
    void batchGetRecipientProfilesShouldRejectEmptyList() {
        var observer = new TestObserver<BatchGetRecipientProfilesResponse>();

        new ProfileConsentGrpcService(mock(RecipientProfileService.class))
                .batchGetRecipientProfiles(BatchGetRecipientProfilesRequest.newBuilder().build(), observer);

        assertThat(status(observer.error)).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void checkRecipientChannelShouldReturnDecision() {
        var service = mock(RecipientProfileService.class);
        when(service.checkRecipientChannel("recipient-1", Channel.CHANNEL_EMAIL, "tenant-a"))
                .thenReturn(new RecipientProfileService.ChannelCheckResult(true, "user@example.test",
                        Channel.CHANNEL_EMAIL, CHECK_REASON_CODE.ALLOWED));
        var observer = new TestObserver<CheckRecipientChannelResponse>();

        new ProfileConsentGrpcService(service).checkRecipientChannel(CheckRecipientChannelRequest.newBuilder()
                .setRecipientId("recipient-1")
                .setTenant("tenant-a")
                .setChannel(Channel.CHANNEL_EMAIL)
                .build(), observer);

        assertThat(observer.value.getAllowed()).isTrue();
        assertThat(observer.value.getDestination()).isEqualTo("user@example.test");
        assertThat(observer.value.getReasonCode()).isEqualTo(CHECK_REASON_CODE.ALLOWED);
    }

    @Test
    void checkRecipientChannelShouldRejectBlankRecipientId() {
        var observer = new TestObserver<CheckRecipientChannelResponse>();

        new ProfileConsentGrpcService(mock(RecipientProfileService.class))
                .checkRecipientChannel(CheckRecipientChannelRequest.newBuilder().build(), observer);

        assertThat(status(observer.error)).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    private static RecipientProfileDomain profile() {
        return new RecipientProfileDomain(
                "recipient-1",
                true,
                Channel.CHANNEL_EMAIL,
                Map.of(Channel.CHANNEL_EMAIL, new ChannelConsent(
                        Channel.CHANNEL_EMAIL,
                        "tenant-a",
                        true,
                        false,
                        "user@example.test"
                )),
                Instant.parse("2026-05-12T10:00:00Z")
        );
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
