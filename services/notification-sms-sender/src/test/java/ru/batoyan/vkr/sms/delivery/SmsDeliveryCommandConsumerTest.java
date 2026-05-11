package ru.batoyan.vkr.sms.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import ru.batoyan.vkr.sms.kafka.gateway.SmsGateway;
import ru.notification.cancellation.proto.v1.CheckDeliveryAllowedResponse;

class SmsDeliveryCommandConsumerTest {

    private static final String STATUS_TOPIC = "notification.sms.delivery-statuses";
    private static final String DELIVERY_ID = "dispatch-1:recipient-1:CHANNEL_SMS";

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldSendSmsAndPublishSentStatusWhenCommandIsAllowed() throws Exception {
        var fixture = new ConsumerFixture();
        when(fixture.dedupService.tryAcquire(eq(DELIVERY_ID), any())).thenReturn(true);
        when(fixture.cancellationClient.checkDeliveryAllowed("dispatch-1", "event-1", "client-1"))
                .thenReturn(CheckDeliveryAllowedResponse.newBuilder().setAllowed(true).build());
        when(fixture.smsGateway.sendBatch(any())).thenReturn(new SmsGateway.BatchSendResult(
                List.of(DELIVERY_ID),
                Map.of()
        ));

        fixture.consumer.onMessage(smsCommandEnvelope());

        verify(fixture.smsGateway).sendBatch(argThat(messages ->
                messages.size() == 1
                        && messages.getFirst().deliveryId().equals(DELIVERY_ID)
                        && messages.getFirst().phone().equals("+10000000000")
        ));
        var statusPayload = capturePayload(fixture.kafkaTemplate);
        assertThat(statusPayload).containsEntry("status", "SMS_DELIVERY_STATUS_SENT");
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

        fixture.consumer.onMessage(smsCommandEnvelope());

        verifyNoInteractions(fixture.smsGateway);
        var statusPayload = capturePayload(fixture.kafkaTemplate);
        assertThat(statusPayload)
                .containsEntry("status", "SMS_DELIVERY_STATUS_CANCELED")
                .containsEntry("error_message", "cancelled by client");
    }

    @Test
    void shouldSkipDuplicateSmsCommandBeforeExternalChecks() {
        var fixture = new ConsumerFixture();
        when(fixture.dedupService.tryAcquire(eq(DELIVERY_ID), any())).thenReturn(false);

        fixture.consumer.onMessage(smsCommandEnvelope());

        verifyNoInteractions(fixture.cancellationClient, fixture.smsGateway);
        verify(fixture.kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void shouldPublishFailedStatusAfterFinalProviderFailure() throws Exception {
        var fixture = new ConsumerFixture();
        when(fixture.dedupService.tryAcquire(eq(DELIVERY_ID), any())).thenReturn(true);
        when(fixture.cancellationClient.checkDeliveryAllowed("dispatch-1", "event-1", "client-1"))
                .thenReturn(CheckDeliveryAllowedResponse.newBuilder().setAllowed(true).build());
        when(fixture.smsGateway.sendBatch(any())).thenReturn(new SmsGateway.BatchSendResult(
                List.of(),
                Map.of(DELIVERY_ID, "sms gateway timeout")
        ));

        fixture.consumer.onMessage(smsCommandEnvelope());

        var statusPayload = capturePayload(fixture.kafkaTemplate);
        assertThat(statusPayload)
                .containsEntry("status", "SMS_DELIVERY_STATUS_FAILED")
                .containsEntry("error_message", "sms gateway timeout");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturePayload(KafkaTemplate<String, String> kafkaTemplate) throws Exception {
        var json = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(STATUS_TOPIC), eq(DELIVERY_ID), json.capture());
        var envelope = objectMapper.readValue(json.getValue(), Map.class);
        return (Map<String, Object>) envelope.get("payload");
    }

    private static String smsCommandEnvelope() {
        return """
                {
                  "outboxId": 1,
                  "aggregateType": "sms_delivery_command",
                  "aggregateId": "dispatch-1:recipient-1:CHANNEL_SMS",
                  "eventType": "SmsDeliveryCommandRequested",
                  "payload": {
                    "dispatch_id": "dispatch-1",
                    "event_id": "event-1",
                    "client_id": "client-1",
                    "recipient_id": "recipient-1",
                    "channel": "CHANNEL_SMS",
                    "destination": "+10000000000",
                    "template_id": "template-1",
                    "template_version": 1,
                    "payload": {"tenant": "tenant-a"}
                  },
                  "headers": {"message_id": "dispatch-1:recipient-1:CHANNEL_SMS"},
                  "createdAt": "2026-05-12T10:00:00Z"
                }
                """;
    }

    @SuppressWarnings("unchecked")
    private final class ConsumerFixture {
        private final SmsGateway smsGateway = Mockito.mock(SmsGateway.class);
        private final KafkaTemplate<String, String> kafkaTemplate = Mockito.mock(KafkaTemplate.class);
        private final SmsRedisDedupService dedupService = Mockito.mock(SmsRedisDedupService.class);
        private final SmsCancellationClient cancellationClient = Mockito.mock(SmsCancellationClient.class);
        private final SmsDeliveryCommandConsumer consumer;

        private ConsumerFixture() {
            when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            consumer = new SmsDeliveryCommandConsumer(
                    objectMapper,
                    smsGateway,
                    kafkaTemplate,
                    dedupService,
                    cancellationClient
            );
            ReflectionTestUtils.setField(consumer, "maxAttempts", 1);
            ReflectionTestUtils.setField(consumer, "retryBackoff", Duration.ZERO);
            ReflectionTestUtils.setField(consumer, "dedupTtl", Duration.ofHours(6));
            ReflectionTestUtils.setField(consumer, "statusTopic", STATUS_TOPIC);
        }
    }
}
