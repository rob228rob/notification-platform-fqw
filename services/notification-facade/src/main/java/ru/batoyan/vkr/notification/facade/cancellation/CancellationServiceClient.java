package ru.batoyan.vkr.notification.facade.cancellation;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import ru.notification.cancellation.proto.v1.CancelDispatchRequest;
import ru.notification.cancellation.proto.v1.CancelDispatchResponse;
import ru.notification.cancellation.proto.v1.CancellationServiceGrpc;

@Component
@RequiredArgsConstructor
public class CancellationServiceClient {

    private final CancellationClientProperties properties;
    private ManagedChannel channel;

    public CancelDispatchResponse cancelDispatch(
            String dispatchId,
            String eventId,
            String clientId,
            String reason,
            String requestedBy
    ) {
        return stub().cancelDispatch(CancelDispatchRequest.newBuilder()
                .setDispatchId(dispatchId)
                .setEventId(eventId == null ? "" : eventId)
                .setClientId(clientId == null ? "" : clientId)
                .setReason(reason == null ? "" : reason)
                .setRequestedBy(requestedBy == null ? "" : requestedBy)
                .build());
    }

    private CancellationServiceGrpc.CancellationServiceBlockingStub stub() {
        return CancellationServiceGrpc.newBlockingStub(channel())
                .withDeadlineAfter(properties.getDeadline().toMillis(), TimeUnit.MILLISECONDS);
    }

    private ManagedChannel channel() {
        if (channel == null) {
            channel = ManagedChannelBuilder.forAddress(properties.getHost(), properties.getPort())
                    .usePlaintext()
                    .build();
        }
        return channel;
    }

    @PreDestroy
    public void close() {
        if (channel != null) {
            channel.shutdown();
        }
    }

    @Configuration
    static class CancellationClientConfig {

        @Bean
        @ConfigurationProperties(prefix = "clients.cancellation")
        CancellationClientProperties cancellationClientProperties() {
            return new CancellationClientProperties();
        }
    }

    @Setter
    @Getter
    static class CancellationClientProperties {
        private String host = "localhost";
        private int port = 9097;
        private Duration deadline = Duration.ofSeconds(3);

    }
}
