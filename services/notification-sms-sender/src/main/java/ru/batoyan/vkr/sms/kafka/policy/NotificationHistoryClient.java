package ru.batoyan.vkr.sms.kafka.policy;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import ru.notification.history.proto.v1.BatchGetRecipientDeliverySummariesRequest;
import ru.notification.history.proto.v1.BatchGetRecipientDeliverySummariesResponse;
import ru.notification.history.proto.v1.NotificationHistoryServiceGrpc;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class NotificationHistoryClient {

    private final NotificationHistoryClientProperties properties;
    private io.grpc.ManagedChannel channel;

    private io.grpc.ManagedChannel channel() {
        if (channel == null) {
            this.channel = io.grpc.ManagedChannelBuilder
                    .forAddress(properties.getHost(), properties.getPort())
                    .usePlaintext()
                    .build();
        }
        return channel;
    }

    private NotificationHistoryServiceGrpc.NotificationHistoryServiceBlockingStub stub() {
        return NotificationHistoryServiceGrpc.newBlockingStub(channel())
                .withDeadlineAfter(properties.getDeadline().toMillis(), TimeUnit.MILLISECONDS);
    }

    public BatchGetRecipientDeliverySummariesResponse batchGetRecipientDeliverySummaries(Collection<String> recipientIds, int lookbackHours) {
        return stub().batchGetRecipientDeliverySummaries(BatchGetRecipientDeliverySummariesRequest.newBuilder()
                .addAllRecipientId(recipientIds)
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

@Configuration
class NotificationHistoryClientConfig {

    @Bean
    @ConfigurationProperties(prefix = "clients.notification-history")
    NotificationHistoryClientProperties notificationHistoryClientProperties() {
        return new NotificationHistoryClientProperties();
    }
}

class NotificationHistoryClientProperties {
    private String host = "localhost";
    private int port = 9091;
    private Duration deadline = Duration.ofSeconds(3);

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public Duration getDeadline() {
        return deadline;
    }

    public void setDeadline(Duration deadline) {
        this.deadline = deadline;
    }
}
