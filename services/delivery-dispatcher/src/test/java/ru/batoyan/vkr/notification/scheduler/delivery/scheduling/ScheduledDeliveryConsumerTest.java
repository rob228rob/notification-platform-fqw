package ru.batoyan.vkr.notification.scheduler.delivery.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.batoyan.vkr.notification.scheduler.delivery.dispatching.DeliveryDispatcherService;

class ScheduledDeliveryConsumerTest {

    private final KafkaEnvelopeParser envelopeParser = mock(KafkaEnvelopeParser.class);
    private final ScheduledDeliveryRepository repository = mock(ScheduledDeliveryRepository.class);
    private final DeliveryDispatcherService deliveryDispatcherService = mock(DeliveryDispatcherService.class);
    private final ScheduledDeliveryConsumer consumer = new ScheduledDeliveryConsumer(
            envelopeParser,
            repository,
            deliveryDispatcherService
    );

    @Test
    void shouldStoreFutureDispatchInsteadOfRoutingImmediately() {
        var envelope = envelope(OffsetDateTime.now().plusHours(1));
        when(envelopeParser.parse("raw")).thenReturn(envelope);
        when(repository.save(envelope)).thenReturn(true);

        consumer.onMessage("raw", Map.of("topic", "delivery.dispatcher"));

        verify(repository).save(envelope);
        verify(deliveryDispatcherService, never()).routeDispatch(envelope);
    }

    @Test
    void shouldRouteDueDispatchWithoutSavingScheduledTask() {
        var envelope = envelope(OffsetDateTime.now().minusMinutes(1));
        when(envelopeParser.parse("raw")).thenReturn(envelope);

        consumer.onMessage("raw", Map.of("topic", "delivery.dispatcher"));

        verify(deliveryDispatcherService).routeDispatch(envelope);
        verify(repository, never()).save(envelope);
    }

    @Test
    void shouldPropagateParserErrorAndAvoidSideEffects() {
        when(envelopeParser.parse("broken")).thenThrow(new IllegalArgumentException("bad envelope"));

        assertThatThrownBy(() -> consumer.onMessage("broken", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad envelope");

        verifyNoInteractions(repository, deliveryDispatcherService);
    }

    @ParameterizedTest
    @CsvSource({
            "planned_send_at,2026-05-12T10:00:00Z",
            "plannedSendAt,2026-05-12T10:01:00Z",
            "send_at,2026-05-12T10:02:00Z",
            "sendAt,2026-05-12T10:03:00Z",
            "planned_send_at,2026-05-12T10:04:00+03:00",
            "plannedSendAt,2026-05-12T10:05:00+03:00",
            "send_at,2026-05-12T10:06:00+03:00",
            "sendAt,2026-05-12T10:07:00+03:00",
            "planned_send_at,2026-05-12T10:08:00+00:00",
            "sendAt,2026-05-12T10:09:00+00:00"
    })
    void shouldResolvePlannedSendAtFromSupportedPayloadAliases(String field, String timestamp) {
        var result = ScheduledDeliveryRepository.resolvePlannedSendAt(Map.of(field, timestamp));

        assertThat(result).isEqualTo(OffsetDateTime.parse(timestamp));
    }

    @ParameterizedTest
    @CsvSource({
            "planned_send_at,not-a-date",
            "plannedSendAt,2026-05-12",
            "send_at,10:00:00",
            "sendAt,2026/05/12T10:00:00Z",
            "planned_send_at,2026-99-12T10:00:00Z",
            "plannedSendAt,2026-05-99T10:00:00Z",
            "send_at,2026-05-12 10:00:00",
            "sendAt,May 12 2026"
    })
    void shouldRejectInvalidPlannedSendAtValues(String field, String timestamp) {
        assertThatThrownBy(() -> ScheduledDeliveryRepository.resolvePlannedSendAt(Map.of(field, timestamp)))
                .isInstanceOf(RuntimeException.class);
    }

    private static KafkaEnvelope envelope(OffsetDateTime plannedSendAt) {
        return new KafkaEnvelope(
                1L,
                "dispatch_request",
                "dispatch-1",
                "DispatchRequested",
                Map.of(
                        "dispatch_id", "dispatch-1",
                        "planned_send_at", plannedSendAt.toString()
                ),
                Map.of("message_id", "message-1"),
                OffsetDateTime.now()
        );
    }
}
