package ru.batoyan.vkr.notification.scheduler.delivery.dispatching;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import ru.notification.profile.proto.v1.BatchGetRecipientProfilesRequest;
import ru.notification.profile.proto.v1.ProfileConsentServiceGrpc;
import ru.notification.profile.proto.v1.RecipientProfile;

@Component
public class ProfileConsentClient {

    private final ProfileConsentClientProperties properties;
    private ManagedChannel channel;

    public ProfileConsentClient(ProfileConsentClientProperties properties) {
        this.properties = properties;
    }

    public Map<String, RecipientProfile> getProfiles(Collection<String> recipientIds, String tenant) {
        var response = stub().batchGetRecipientProfiles(BatchGetRecipientProfilesRequest.newBuilder()
                .addAllRecipientId(recipientIds)
                .setTenant(tenant == null ? "" : tenant)
                .build());
        var profiles = new LinkedHashMap<String, RecipientProfile>();
        response.getProfilesList().forEach(profile -> profiles.put(profile.getRecipientId(), profile));
        return profiles;
    }

    private ProfileConsentServiceGrpc.ProfileConsentServiceBlockingStub stub() {
        return ProfileConsentServiceGrpc.newBlockingStub(channel())
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
        @ConfigurationProperties(prefix = "clients.profile-consent")
        ProfileConsentClientProperties profileConsentClientProperties() {
            return new ProfileConsentClientProperties();
        }
    }

    static class ProfileConsentClientProperties {
        private String host = "localhost";
        private int port = 9096;
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
}
