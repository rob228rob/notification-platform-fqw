package ru.batoyan.vkr.notification.profile.grpc;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import ru.batoyan.vkr.notification.profile.model.RecipientProfileDomain;
import ru.batoyan.vkr.notification.profile.service.RecipientProfileService;
import ru.notification.profile.proto.v1.*;

import java.time.Instant;
import java.util.Collection;

@GrpcService
@Slf4j
@RequiredArgsConstructor
public class ProfileConsentGrpcService extends ProfileConsentServiceGrpc.ProfileConsentServiceImplBase {

    private final RecipientProfileService recipientProfileService;

    @Override
    public void getRecipientProfile(GetRecipientProfileRequest request, StreamObserver<GetRecipientProfileResponse> responseObserver) {
        validateRecipientId(request.getRecipientId(), "recipient_id", responseObserver);
        log.debug("gRPC getRecipientProfile called: recipientId={}", request.getRecipientId());
        recipientProfileService.getRecipientProfile(request.getRecipientId())
            .ifPresentOrElse(profile -> {
                log.debug("gRPC getRecipientProfile success: recipientId={}, preferredChannel={}, active={}",
                    request.getRecipientId(), profile.preferredChannel(), profile.active());
                responseObserver.onNext(GetRecipientProfileResponse.newBuilder()
                    .setProfile(profileToProto(profile))
                    .build());
                responseObserver.onCompleted();
            }, () -> {
                log.debug("gRPC getRecipientProfile not found: recipientId={}", request.getRecipientId());
                responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Recipient profile not found: " + request.getRecipientId())
                    .asRuntimeException());
            });
    }

    @Override
    public void batchGetRecipientProfiles(BatchGetRecipientProfilesRequest request,
                                          StreamObserver<BatchGetRecipientProfilesResponse> responseObserver) {
        validateRecipientIds(request.getRecipientIdList(), "recipient_id", responseObserver);
        log.debug("gRPC batchGetRecipientProfiles called: recipientIdsCount={}", request.getRecipientIdCount());
        var response = BatchGetRecipientProfilesResponse.newBuilder();
        var profiles = recipientProfileService.getRecipientProfiles(request.getRecipientIdList());
        profiles.values()
            .forEach(profile -> response.addProfiles(profileToProto(profile)));
        log.debug("gRPC batchGetRecipientProfiles completed: requested={}, found={}",
            request.getRecipientIdCount(), profiles.size());
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    @Override
    public void checkRecipientChannel(CheckRecipientChannelRequest request,
                                      StreamObserver<CheckRecipientChannelResponse> responseObserver) {
        if (!validateRecipientId(request.getRecipientId(), "recipient_id", responseObserver)) {
            return;
        }
        log.debug("gRPC checkRecipientChannel called: recipientId={}, channel={}",
            request.getRecipientId(), request.getChannel());
        var result = recipientProfileService.checkRecipientChannel(request.getRecipientId(), request.getChannel());
        log.debug("gRPC checkRecipientChannel result: recipientId={}, channel={}, allowed={}, reasonCode={}, preferredChannel={}, destination={}",
            request.getRecipientId(), request.getChannel(), result.allowed(), result.reasonCode(),
            result.preferredChannel(), result.destination());
        responseObserver.onNext(CheckRecipientChannelResponse.newBuilder()
            .setAllowed(result.allowed())
            .setDestination(result.destination())
            .setPreferredChannel(result.preferredChannel())
            .setReasonCode(result.reasonCode())
            .setCheckedAt(timestamp(Instant.now()))
            .build());
        responseObserver.onCompleted();
    }

    private RecipientProfile profileToProto(RecipientProfileDomain profile) {
        var builder = RecipientProfile.newBuilder()
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

    private boolean validateRecipientId(String recipientId, String fieldName, StreamObserver<?> responseObserver) {
        if (recipientId.isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                .withDescription(fieldName + " must not be blank")
                .asRuntimeException());
            return false;
        }
        return true;
    }

    private boolean validateRecipientIds(Collection<String> recipientIds, String fieldName, StreamObserver<?> responseObserver) {
        if (recipientIds.isEmpty()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                .withDescription(fieldName + " must not be empty")
                .asRuntimeException());
            return false;
        }

        var hasInvalidRecipientId = recipientIds.stream().anyMatch(recipientId -> recipientId == null || recipientId.isBlank());
        if (hasInvalidRecipientId) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                .withDescription(fieldName + " must not contain blank values")
                .asRuntimeException());
            return false;
        }

        return true;
    }
}
