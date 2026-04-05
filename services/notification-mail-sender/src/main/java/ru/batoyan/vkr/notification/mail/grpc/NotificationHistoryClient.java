package ru.batoyan.vkr.notification.mail.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ClientInterceptors;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.notification.history.proto.v1.GetRecipientDeliverySummaryRequest;
import ru.notification.history.proto.v1.GetRecipientDeliverySummaryResponse;
import ru.notification.history.proto.v1.NotificationHistoryServiceGrpc;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class NotificationHistoryClient {

    private final NotificationHistoryClientProperties properties;
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

    private NotificationHistoryServiceGrpc.NotificationHistoryServiceBlockingStub stub() {
        return NotificationHistoryServiceGrpc.newBlockingStub(ClientInterceptors.intercept(channel(), metricsInterceptor))
                .withDeadlineAfter(properties.getDeadline().toMillis(), TimeUnit.MILLISECONDS);
    }

    public GetRecipientDeliverySummaryResponse getRecipientDeliverySummary(String recipientId, int lookbackHours) {
        return stub().getRecipientDeliverySummary(GetRecipientDeliverySummaryRequest.newBuilder()
                .setRecipientId(recipientId)
                .setLookbackHours(lookbackHours)
                .build());
    }

    @PreDestroy
    public void close() {
        if (channel != null) {
            channel.shutdown();
        }
    }
}
