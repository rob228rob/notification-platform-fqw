package ru.batoyan.vkr.notification.scheduler.delivery.dispatching;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import ru.notification.cancellation.proto.v1.CancellationServiceGrpc;
import ru.notification.cancellation.proto.v1.CheckDeliveryAllowedRequest;
import ru.notification.cancellation.proto.v1.CheckDeliveryAllowedResponse;

@Component
public class CancellationServiceClient {

    private final CancellationClientProperties properties;
    private ManagedChannel channel;

    public CancellationServiceClient(CancellationClientProperties properties) {
        this.properties = properties;
    }

    public CheckDeliveryAllowedResponse checkDeliveryAllowed(String dispatchId, String eventId, String clientId) {
        return stub().checkDeliveryAllowed(CheckDeliveryAllowedRequest.newBuilder()
                .setDispatchId(dispatchId)
                .setEventId(eventId == null ? "" : eventId)
                .setClientId(clientId == null ? "" : clientId)
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
    static class ClientConfig {

        @Bean
        @ConfigurationProperties(prefix = "clients.cancellation")
        CancellationClientProperties cancellationClientProperties() {
            return new CancellationClientProperties();
        }
    }

    @Setter
    @Getter
    public static class CancellationClientProperties {
        private String host = "localhost";
        private int port = 9097;
        private Duration deadline = Duration.ofSeconds(3);

    }
}
