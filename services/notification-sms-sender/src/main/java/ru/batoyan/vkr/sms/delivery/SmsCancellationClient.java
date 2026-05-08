package ru.batoyan.vkr.sms.delivery;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.notification.cancellation.proto.v1.CancellationServiceGrpc;
import ru.notification.cancellation.proto.v1.CheckDeliveryAllowedRequest;
import ru.notification.cancellation.proto.v1.CheckDeliveryAllowedResponse;

@Component
public class SmsCancellationClient {

    @Value("${clients.cancellation.host:localhost}")
    private String host;

    @Value("${clients.cancellation.port:9097}")
    private int port;

    @Value("${clients.cancellation.deadline:PT3S}")
    private Duration deadline;

    private ManagedChannel channel;

    public CheckDeliveryAllowedResponse checkDeliveryAllowed(String dispatchId, String eventId, String clientId) {
        return stub().checkDeliveryAllowed(CheckDeliveryAllowedRequest.newBuilder()
                .setDispatchId(dispatchId)
                .setEventId(eventId)
                .setClientId(clientId)
                .build());
    }

    private CancellationServiceGrpc.CancellationServiceBlockingStub stub() {
        return CancellationServiceGrpc.newBlockingStub(channel())
                .withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    private ManagedChannel channel() {
        if (channel == null) {
            channel = ManagedChannelBuilder.forAddress(host, port)
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
}
