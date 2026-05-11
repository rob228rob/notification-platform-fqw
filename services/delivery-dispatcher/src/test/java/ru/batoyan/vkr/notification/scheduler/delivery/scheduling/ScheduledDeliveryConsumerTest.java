package ru.batoyan.vkr.notification.scheduler.delivery.scheduling;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
