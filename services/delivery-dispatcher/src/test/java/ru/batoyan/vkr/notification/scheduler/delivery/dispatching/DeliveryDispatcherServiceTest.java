package ru.batoyan.vkr.notification.scheduler.delivery.dispatching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import ru.batoyan.vkr.notification.scheduler.delivery.config.SchedulerDeliveryProperties;
import ru.batoyan.vkr.notification.scheduler.delivery.scheduling.KafkaEnvelope;
import ru.notification.cancellation.proto.v1.CheckDeliveryAllowedResponse;
import ru.notification.common.proto.v1.Channel;
import ru.notification.profile.proto.v1.RecipientChannelSettings;
import ru.notification.profile.proto.v1.RecipientProfile;

class DeliveryDispatcherServiceTest {

    @Test
    void nextFallbackChannelSkipsAllPreviousChannels() {
        var nextChannel = DeliveryDispatcherService.nextFallbackChannel(
                List.of("CHANNEL_EMAIL", "CHANNEL_SMS"),
                List.of("CHANNEL_EMAIL")
        );

        assertThat(nextChannel).isEqualTo("CHANNEL_SMS");
    }

    @Test
    void nextFallbackChannelReturnsNullWhenNoChannelsRemain() {
        var nextChannel = DeliveryDispatcherService.nextFallbackChannel(
                List.of("CHANNEL_EMAIL", "CHANNEL_SMS"),
                List.of("CHANNEL_EMAIL", "CHANNEL_SMS")
        );

        assertThat(nextChannel).isNull();
    }

    @Test
    void mergePreviousChannelsAddsCurrentChannelOnlyOnce() {
        var channels = DeliveryDispatcherService.mergePreviousChannels(
                List.of("CHANNEL_EMAIL", "CHANNEL_EMAIL"),
                "CHANNEL_EMAIL"
        );

        assertThat(channels).containsExactly("CHANNEL_EMAIL");
    }

    @Test
    void mergePreviousChannelsIgnoresBlankAndNullValues() {
        var channels = DeliveryDispatcherService.mergePreviousChannels(
                java.util.Arrays.asList("CHANNEL_EMAIL", "", null),
                "CHANNEL_SMS"
        );

        assertThat(channels).containsExactly("CHANNEL_EMAIL", "CHANNEL_SMS");
    }

    @Test
    void nextFallbackChannelReturnsFirstUnvisitedChannel() {
        var nextChannel = DeliveryDispatcherService.nextFallbackChannel(
                List.of("CHANNEL_EMAIL", "CHANNEL_SMS", "CHANNEL_PUSH"),
                List.of("CHANNEL_EMAIL")
        );

        assertThat(nextChannel).isEqualTo("CHANNEL_SMS");
    }

    @Test
    void nextFallbackChannelReturnsNullForEmptyFallbackList() {
        assertThat(DeliveryDispatcherService.nextFallbackChannel(List.of(), List.of("CHANNEL_EMAIL"))).isNull();
    }

    @Test
    void routeDispatchPublishesMailCommandForAllowedRecipient() {
        var fixture = new Fixture();
        when(fixture.cancellationClient.checkDeliveryAllowed("dispatch-1", "event-1", "client-1"))
                .thenReturn(CheckDeliveryAllowedResponse.newBuilder().setAllowed(true).build());
        when(fixture.profileConsentClient.getProfiles(List.of("recipient-1"), "tenant-a"))
                .thenReturn(Map.of("recipient-1", profile(Channel.CHANNEL_EMAIL, "user@example.test")));

        fixture.service.routeDispatch(dispatchEnvelope("CHANNEL_EMAIL", List.of("recipient-1")));

        verify(fixture.kafkaTemplate).send(eq("notification.mail.dispatches"), eq("dispatch-1:recipient-1:CHANNEL_EMAIL"), anyString());
    }

    @Test
    void routeDispatchPublishesSkippedStatusWhenProfileIsUnavailable() {
        var fixture = new Fixture();
        when(fixture.cancellationClient.checkDeliveryAllowed("dispatch-1", "event-1", "client-1"))
                .thenReturn(CheckDeliveryAllowedResponse.newBuilder().setAllowed(true).build());
        when(fixture.profileConsentClient.getProfiles(List.of("recipient-1"), "tenant-a"))
                .thenReturn(Map.of());

        fixture.service.routeDispatch(dispatchEnvelope("CHANNEL_EMAIL", List.of("recipient-1")));

        verify(fixture.kafkaTemplate).send(eq("notification.mail.delivery-statuses"), eq("dispatch-1:recipient-1:CHANNEL_EMAIL"), anyString());
    }

    @Test
    void routeDispatchPublishesCanceledStatusWhenCancellationServiceDeniesDelivery() {
        var fixture = new Fixture();
        when(fixture.cancellationClient.checkDeliveryAllowed("dispatch-1", "event-1", "client-1"))
                .thenReturn(CheckDeliveryAllowedResponse.newBuilder().setAllowed(false).setReason("cancelled").build());

        fixture.service.routeDispatch(dispatchEnvelope("CHANNEL_SMS", List.of("recipient-1")));

        verify(fixture.kafkaTemplate).send(eq("notification.sms.delivery-statuses"), eq("dispatch-1:recipient-1:CHANNEL_SMS"), anyString());
    }

    @SuppressWarnings("unchecked")
    private static final class Fixture {
        private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        private final ProfileConsentClient profileConsentClient = mock(ProfileConsentClient.class);
        private final CancellationServiceClient cancellationClient = mock(CancellationServiceClient.class);
        private final DeliveryDispatcherService service;

        private Fixture() {
            when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            var properties = new SchedulerDeliveryProperties();
            service = new DeliveryDispatcherService(
                    kafkaTemplate,
                    new ObjectMapper(),
                    profileConsentClient,
                    cancellationClient,
                    properties
            );
        }
    }

    private static KafkaEnvelope dispatchEnvelope(String channel, List<String> recipientIds) {
        return new KafkaEnvelope(
                1L,
                "dispatch_request",
                "dispatch-1",
                "DispatchRequested",
                Map.of(
                        "dispatch_id", "dispatch-1",
                        "event_id", "event-1",
                        "client_id", "client-1",
                        "preferred_channel", channel,
                        "template_id", "template-1",
                        "template_version", 1,
                        "payload", Map.of("tenant", "tenant-a"),
                        "recipient_ids", recipientIds,
                        "fallback_channels", List.of("CHANNEL_EMAIL", "CHANNEL_SMS")
                ),
                Map.of(),
                OffsetDateTime.now()
        );
    }

    private static RecipientProfile profile(Channel channel, String destination) {
        return RecipientProfile.newBuilder()
                .setRecipientId("recipient-1")
                .setActive(true)
                .addChannels(RecipientChannelSettings.newBuilder()
                        .setChannel(channel)
                        .setEnabled(true)
                        .setBlacklisted(false)
                        .setDestination(destination)
                        .build())
                .build();
    }
}
