package ru.batoyan.vkr.notification.mail.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import ru.batoyan.vkr.notification.mail.grpc.ProfileConsentClient;
import ru.batoyan.vkr.notification.mail.kafka.gateway.MailGateway;
import ru.batoyan.vkr.notification.mail.kafka.gateway.MailProviderGuardService;
import ru.notification.cancellation.proto.v1.CheckDeliveryAllowedResponse;
import ru.notification.profile.proto.v1.CHECK_REASON_CODE;
import ru.notification.profile.proto.v1.CheckRecipientChannelResponse;

class MailDeliveryCommandConsumerTest {

    private static final String STATUS_TOPIC = "notification.mail.delivery-statuses";
    private static final String FALLBACK_TOPIC = "delivery.fallback";
    private static final String DELIVERY_ID = "dispatch-1:recipient-1:CHANNEL_EMAIL";

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void fallbackPreviousChannelsContainsCurrentChannelAfterFinalSenderError() {
        var previousChannels = MailDeliveryCommandConsumer.fallbackPreviousChannels(
                List.of("CHANNEL_EMAIL"),
                "CHANNEL_EMAIL"
        );

        assertThat(previousChannels).containsExactly("CHANNEL_EMAIL");
    }

    @Test
    void fallbackPreviousChannelsAppendsCurrentChannelWhenMissing() {
        var previousChannels = MailDeliveryCommandConsumer.fallbackPreviousChannels(
                List.of("CHANNEL_SMS"),
                "CHANNEL_EMAIL"
        );

        assertThat(previousChannels).containsExactly("CHANNEL_SMS", "CHANNEL_EMAIL");
    }

    @Test
    void fallbackPreviousChannelsIgnoresBlankValues() {
        var previousChannels = MailDeliveryCommandConsumer.fallbackPreviousChannels(
                java.util.Arrays.asList("", null, "CHANNEL_EMAIL"),
                " "
        );

        assertThat(previousChannels).containsExactly("CHANNEL_EMAIL");
    }

    @Test
    void fallbackPreviousChannelsKeepsOrderAndRemovesDuplicates() {
        var previousChannels = MailDeliveryCommandConsumer.fallbackPreviousChannels(
                List.of("CHANNEL_EMAIL", "CHANNEL_SMS", "CHANNEL_EMAIL"),
                "CHANNEL_PUSH"
        );

        assertThat(previousChannels).containsExactly("CHANNEL_EMAIL", "CHANNEL_SMS", "CHANNEL_PUSH");
    }

    @Test
    void providerGuardAllowsMailWhenProfileConsentAllowsEmail() {
        var profileClient = mock(ProfileConsentClient.class);
        when(profileClient.checkRecipientChannel("recipient-1", ru.notification.common.proto.v1.Channel.CHANNEL_EMAIL))
                .thenReturn(CheckRecipientChannelResponse.newBuilder()
                        .setAllowed(true)
                        .setDestination("user@example.test")
                        .setReasonCode(CHECK_REASON_CODE.ALLOWED)
                        .build());
        var guard = new MailProviderGuardService(profileClient, Runnable::run);
        var message = mailMessage("delivery-1", "recipient-1");

        var result = guard.validateBatch(List.of(message));

        assertThat(result.allowedMessages()).containsExactly(message);
        assertThat(result.rejectedDeliveries()).isEmpty();
    }

    @Test
    void providerGuardRejectsMailWhenProfileConsentDeniesEmail() {
        var profileClient = mock(ProfileConsentClient.class);
        when(profileClient.checkRecipientChannel("recipient-1", ru.notification.common.proto.v1.Channel.CHANNEL_EMAIL))
                .thenReturn(CheckRecipientChannelResponse.newBuilder()
                        .setAllowed(false)
                        .setReasonCode(CHECK_REASON_CODE.CHANNEL_DISABLED)
                        .build());
        var guard = new MailProviderGuardService(profileClient, Runnable::run);

        var result = guard.validateBatch(List.of(mailMessage("delivery-1", "recipient-1")));

        assertThat(result.allowedMessages()).isEmpty();
        assertThat(result.rejectedDeliveries()).containsEntry("delivery-1", "CHANNEL_DISABLED");
    }

    @Test
    void providerGuardRejectsMailWhenProfileConsentIsUnavailable() {
        var profileClient = mock(ProfileConsentClient.class);
        when(profileClient.checkRecipientChannel("recipient-1", ru.notification.common.proto.v1.Channel.CHANNEL_EMAIL))
                .thenThrow(new IllegalStateException("grpc unavailable"));
        var guard = new MailProviderGuardService(profileClient, Runnable::run);

        var result = guard.validateBatch(List.of(mailMessage("delivery-1", "recipient-1")));

        assertThat(result.allowedMessages()).isEmpty();
        assertThat(result.rejectedDeliveries()).containsEntry("delivery-1", "PROFILE_CONSENT_UNAVAILABLE");
    }

    @Test
    void providerGuardHandlesEmptyBatch() {
        var guard = new MailProviderGuardService(mock(ProfileConsentClient.class), Runnable::run);

        var result = guard.validateBatch(List.of());

        assertThat(result.allowedMessages()).isEmpty();
        assertThat(result.rejectedDeliveries()).isEmpty();
    }

    @Test
    void batchSendResultSeparatesSuccessAndFailures() {
        var result = new MailGateway.BatchSendResult(
                List.of("delivery-1"),
                Map.of("delivery-2", "timeout")
        );

        assertThat(result.succeededDeliveryIds()).containsExactly("delivery-1");
        assertThat(result.failedDeliveryErrors()).containsEntry("delivery-2", "timeout");
    }

    @Test
    void shouldSendMailAndPublishSentStatusWhenCommandIsAllowed() throws Exception {
        var fixture = new ConsumerFixture();
        when(fixture.dedupService.tryAcquire(eq(DELIVERY_ID), any())).thenReturn(true);
        when(fixture.cancellationClient.checkDeliveryAllowed("dispatch-1", "event-1", "client-1"))
                .thenReturn(CheckDeliveryAllowedResponse.newBuilder().setAllowed(true).build());
        when(fixture.mailGateway.sendBatch(any())).thenReturn(new MailGateway.BatchSendResult(
                List.of(DELIVERY_ID),
                Map.of()
        ));

        fixture.consumer.onMessage(mailCommandEnvelope());

        verify(fixture.mailGateway).sendBatch(argThat(messages ->
                messages.size() == 1
                        && messages.getFirst().deliveryId().equals(DELIVERY_ID)
                        && messages.getFirst().email().equals("user@example.test")
        ));
        var statusPayload = capturePayload(fixture.kafkaTemplate, STATUS_TOPIC);
        assertThat(statusPayload).containsEntry("status", "MAIL_DELIVERY_STATUS_SENT");
        assertThat(statusPayload).containsEntry("attempt_no", 1);
    }

    @Test
    void shouldPublishCanceledStatusWithoutProviderCallWhenDispatchIsCancelled() throws Exception {
        var fixture = new ConsumerFixture();
        when(fixture.dedupService.tryAcquire(eq(DELIVERY_ID), any())).thenReturn(true);
        when(fixture.cancellationClient.checkDeliveryAllowed("dispatch-1", "event-1", "client-1"))
                .thenReturn(CheckDeliveryAllowedResponse.newBuilder()
                        .setAllowed(false)
                        .setReason("cancelled by client")
                        .build());

        fixture.consumer.onMessage(mailCommandEnvelope());

        verifyNoInteractions(fixture.mailGateway);
        var statusPayload = capturePayload(fixture.kafkaTemplate, STATUS_TOPIC);
        assertThat(statusPayload)
                .containsEntry("status", "MAIL_DELIVERY_STATUS_CANCELED")
                .containsEntry("error_message", "cancelled by client");
    }

    @Test
    void shouldSkipDuplicateMailCommandBeforeExternalChecks() {
        var fixture = new ConsumerFixture();
        when(fixture.dedupService.tryAcquire(eq(DELIVERY_ID), any())).thenReturn(false);

        fixture.consumer.onMessage(mailCommandEnvelope());

        verifyNoInteractions(fixture.cancellationClient, fixture.mailGateway);
        verify(fixture.kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void shouldPublishFailedStatusAndFallbackEventAfterFinalProviderFailure() throws Exception {
        var fixture = new ConsumerFixture();
        when(fixture.dedupService.tryAcquire(eq(DELIVERY_ID), any())).thenReturn(true);
        when(fixture.cancellationClient.checkDeliveryAllowed("dispatch-1", "event-1", "client-1"))
                .thenReturn(CheckDeliveryAllowedResponse.newBuilder().setAllowed(true).build());
        when(fixture.mailGateway.sendBatch(any())).thenReturn(new MailGateway.BatchSendResult(
                List.of(),
                Map.of(DELIVERY_ID, "smtp timeout")
        ));

        fixture.consumer.onMessage(mailCommandEnvelope());

        var statusPayload = capturePayload(fixture.kafkaTemplate, STATUS_TOPIC);
        assertThat(statusPayload)
                .containsEntry("status", "MAIL_DELIVERY_STATUS_FAILED")
                .containsEntry("error_message", "smtp timeout");
        var fallbackPayload = capturePayload(fixture.kafkaTemplate, FALLBACK_TOPIC);
        assertThat(fallbackPayload)
                .containsEntry("dispatch_id", "dispatch-1")
                .containsEntry("error_message", "smtp timeout");
        assertThat(fallbackPayload.get("previous_channels"))
                .asList()
                .containsExactly("CHANNEL_EMAIL");
    }

    private static MailGateway.MailMessage mailMessage(String deliveryId, String recipientId) {
        return new MailGateway.MailMessage(
                deliveryId,
                deliveryId,
                recipientId,
                "user@example.test",
                "template-1",
                1,
                "{}"
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturePayload(KafkaTemplate<String, String> kafkaTemplate, String topic) throws Exception {
        var json = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(topic), eq(DELIVERY_ID), json.capture());
        var envelope = objectMapper.readValue(json.getValue(), Map.class);
        return (Map<String, Object>) envelope.get("payload");
    }

    private String mailCommandEnvelope() {
        return """
                {
                  "outboxId": 1,
                  "aggregateType": "mail_delivery_command",
                  "aggregateId": "dispatch-1:recipient-1:CHANNEL_EMAIL",
                  "eventType": "MailDeliveryCommandRequested",
                  "payload": {
                    "dispatch_id": "dispatch-1",
                    "event_id": "event-1",
                    "client_id": "client-1",
                    "recipient_id": "recipient-1",
                    "channel": "CHANNEL_EMAIL",
                    "destination": "user@example.test",
                    "template_id": "template-1",
                    "template_version": 1,
                    "payload": {"tenant": "tenant-a"},
                    "fallback_depth": 0,
                    "previous_channels": [],
                    "fallback_channels": ["CHANNEL_SMS"]
                  },
                  "headers": {"message_id": "dispatch-1:recipient-1:CHANNEL_EMAIL"},
                  "createdAt": "2026-05-12T10:00:00Z"
                }
                """;
    }

    @SuppressWarnings("unchecked")
    private final class ConsumerFixture {
        private final MailGateway mailGateway = mock(MailGateway.class);
        private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        private final MailRedisDedupService dedupService = mock(MailRedisDedupService.class);
        private final MailCancellationClient cancellationClient = mock(MailCancellationClient.class);
        private final MailDeliveryCommandConsumer consumer;

        private ConsumerFixture() {
            when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            consumer = new MailDeliveryCommandConsumer(
                    objectMapper,
                    mailGateway,
                    kafkaTemplate,
                    dedupService,
                    cancellationClient
            );
            ReflectionTestUtils.setField(consumer, "maxAttempts", 1);
            ReflectionTestUtils.setField(consumer, "retryBackoff", Duration.ZERO);
            ReflectionTestUtils.setField(consumer, "dedupTtl", Duration.ofHours(6));
            ReflectionTestUtils.setField(consumer, "statusTopic", STATUS_TOPIC);
            ReflectionTestUtils.setField(consumer, "fallbackTopic", FALLBACK_TOPIC);
        }
    }
}
