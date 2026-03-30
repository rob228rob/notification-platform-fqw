package ru.batoyan.vkr.notification.sms.sender.services.kafka.policy;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import ru.notification.common.proto.v1.Channel;
import ru.notification.profile.proto.v1.BatchGetRecipientProfilesRequest;
import ru.notification.profile.proto.v1.CheckRecipientChannelRequest;
import ru.notification.profile.proto.v1.CheckRecipientChannelResponse;
import ru.notification.profile.proto.v1.ProfileConsentServiceGrpc;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
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

    public Map<String, RecipientDecision> batchGetSmsDecisions(Collection<String> recipientIds) {
        var response = stub().batchGetRecipientProfiles(BatchGetRecipientProfilesRequest.newBuilder()
                .addAllRecipientId(recipientIds)
                .build());

        var result = new LinkedHashMap<String, RecipientDecision>();
        for (var profile : response.getProfilesList()) {
            var consent = profile.getChannelsList().stream()
                    .filter(channel -> channel.getChannel() == Channel.CHANNEL_SMS)
                    .findFirst()
                    .orElse(null);

            if (!profile.getActive()) {
                result.put(profile.getRecipientId(), new RecipientDecision(false, "", "PROFILE_INACTIVE"));
            } else if (consent == null) {
                result.put(profile.getRecipientId(), new RecipientDecision(false, "", "SMS_CHANNEL_MISSING"));
            } else if (!consent.getEnabled()) {
                result.put(profile.getRecipientId(), new RecipientDecision(false, consent.getDestination(), "CHANNEL_DISABLED"));
            } else if (consent.getBlacklisted()) {
                result.put(profile.getRecipientId(), new RecipientDecision(false, consent.getDestination(), "CHANNEL_BLACKLISTED"));
            } else if (consent.getDestination().isBlank()) {
                result.put(profile.getRecipientId(), new RecipientDecision(false, "", "DESTINATION_MISSING"));
            } else {
                result.put(profile.getRecipientId(), new RecipientDecision(true, consent.getDestination(), "ALLOWED"));
            }
        }

        for (var recipientId : recipientIds) {
            result.putIfAbsent(recipientId, new RecipientDecision(false, "", "PROFILE_NOT_FOUND"));
        }
        return result;
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

@Configuration
class ProfileConsentClientConfig {

    @Bean
    @ConfigurationProperties(prefix = "clients.profile-consent")
    ProfileConsentClientProperties profileConsentClientProperties() {
        return new ProfileConsentClientProperties();
    }
}

class ProfileConsentClientProperties {
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
