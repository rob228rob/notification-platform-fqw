package ru.batoyan.vkr.notification.sms.sender.services.kafka.policy;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.notification.common.proto.v1.Channel;
import ru.notification.profile.proto.v1.CheckRecipientChannelRequest;
import ru.notification.profile.proto.v1.CheckRecipientChannelResponse;
import ru.notification.profile.proto.v1.ProfileConsentServiceGrpc;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class ProfileConsentClient {

    private final ProfileConsentClientProperties properties;
    private io.grpc.ManagedChannel channel;
    private ProfileConsentServiceGrpc.ProfileConsentServiceBlockingStub stub;

    private ProfileConsentServiceGrpc.ProfileConsentServiceBlockingStub stub() {
        if (stub == null) {
            this.channel = io.grpc.ManagedChannelBuilder
                    .forAddress(properties.getHost(), properties.getPort())
                    .usePlaintext()
                    .build();
            this.stub = ProfileConsentServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(properties.getDeadline().toMillis(), TimeUnit.MILLISECONDS);
        }
        return stub;
    }

    public CheckRecipientChannelResponse checkRecipientChannel(String recipientId, Channel channel) {
        return stub().checkRecipientChannel(CheckRecipientChannelRequest.newBuilder()
                .setRecipientId(recipientId)
                .setChannel(channel)
                .build());
    }

    @PreDestroy
    public void close() {
        if (channel != null) {
            channel.shutdown();
        }
    }
}
