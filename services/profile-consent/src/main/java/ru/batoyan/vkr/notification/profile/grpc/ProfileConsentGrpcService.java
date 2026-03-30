package ru.batoyan.vkr.notification.profile.grpc;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import ru.batoyan.vkr.notification.profile.model.RecipientProfile;
import ru.batoyan.vkr.notification.profile.service.RecipientProfileService;
import ru.notification.profile.proto.v1.BatchGetRecipientProfilesRequest;
import ru.notification.profile.proto.v1.BatchGetRecipientProfilesResponse;
import ru.notification.profile.proto.v1.CheckRecipientChannelRequest;
import ru.notification.profile.proto.v1.CheckRecipientChannelResponse;
import ru.notification.profile.proto.v1.GetRecipientProfileRequest;
import ru.notification.profile.proto.v1.GetRecipientProfileResponse;
import ru.notification.profile.proto.v1.ProfileConsentServiceGrpc;
import ru.notification.profile.proto.v1.RecipientChannelSettings;

import java.time.Instant;

@GrpcService
@RequiredArgsConstructor
public class ProfileConsentGrpcService extends ProfileConsentServiceGrpc.ProfileConsentServiceImplBase {

    private final RecipientProfileService recipientProfileService;

    @Override
    public void getRecipientProfile(GetRecipientProfileRequest request, StreamObserver<GetRecipientProfileResponse> responseObserver) {
        recipientProfileService.getRecipientProfile(request.getRecipientId())
                .ifPresentOrElse(profile -> {
                    responseObserver.onNext(GetRecipientProfileResponse.newBuilder()
                            .setProfile(profileToProto(profile))
                            .build());
                    responseObserver.onCompleted();
                }, () -> responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Recipient profile not found: " + request.getRecipientId())
                        .asRuntimeException()));
    }

    @Override
    public void batchGetRecipientProfiles(BatchGetRecipientProfilesRequest request,
                                          StreamObserver<BatchGetRecipientProfilesResponse> responseObserver) {
        var response = BatchGetRecipientProfilesResponse.newBuilder();
        recipientProfileService.getRecipientProfiles(request.getRecipientIdList())
                .values()
                .forEach(profile -> response.addProfiles(profileToProto(profile)));
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    @Override
    public void checkRecipientChannel(CheckRecipientChannelRequest request,
                                      StreamObserver<CheckRecipientChannelResponse> responseObserver) {
        var result = recipientProfileService.checkRecipientChannel(request.getRecipientId(), request.getChannel());
        responseObserver.onNext(CheckRecipientChannelResponse.newBuilder()
                .setAllowed(result.allowed())
                .setDestination(result.destination())
                .setPreferredChannel(result.preferredChannel())
                .setReasonCode(result.reasonCode())
                .setCheckedAt(timestamp(Instant.now()))
                .build());
        responseObserver.onCompleted();
    }

    private ru.notification.profile.proto.v1.RecipientProfile profileToProto(RecipientProfile profile) {
        var builder = ru.notification.profile.proto.v1.RecipientProfile.newBuilder()
                .setRecipientId(profile.recipientId())
                .setActive(profile.active())
                .setPreferredChannel(profile.preferredChannel())
                .setUpdatedAt(timestamp(profile.updatedAt()));

        profile.channels().values()
                .forEach(channel -> builder.addChannels(RecipientChannelSettings.newBuilder()
                        .setChannel(channel.channel())
                        .setEnabled(channel.enabled())
                        .setBlacklisted(channel.blacklisted())
                        .setDestination(channel.destination())
                        .build()));
        return builder.build();
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.newBuilder()
                .setSeconds(value.getEpochSecond())
                .setNanos(value.getNano())
                .build();
    }
}
