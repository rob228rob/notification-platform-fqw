package ru.batoyan.vkr.notification.mail.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ClientInterceptors;
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
    private final GrpcClientMetricsInterceptor metricsInterceptor;
    private ManagedChannel channel;

    private ManagedChannel channel() {
        if (channel == null) {
            this.channel = ManagedChannelBuilder
                    .forAddress(properties.getHost(), properties.getPort())
                    .usePlaintext()
                    .build();
        }
        return channel;
    }

    private ProfileConsentServiceGrpc.ProfileConsentServiceBlockingStub stub() {
        return ProfileConsentServiceGrpc.newBlockingStub(ClientInterceptors.intercept(channel(), metricsInterceptor))
                .withDeadlineAfter(properties.getDeadline().toMillis(), TimeUnit.MILLISECONDS);
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
