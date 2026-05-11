package ru.batoyan.vkr.notification.scheduler.delivery.dispatching;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.batoyan.vkr.notification.scheduler.delivery.config.SchedulerDeliveryProperties;
import ru.batoyan.vkr.notification.scheduler.delivery.scheduling.KafkaEnvelope;
import ru.batoyan.vkr.notification.scheduler.delivery.scheduling.KafkaEnvelopeParser;

class FallbackDeliveryConsumerTest {

    private final KafkaEnvelopeParser envelopeParser = mock(KafkaEnvelopeParser.class);
    private final DeliveryDispatcherService deliveryDispatcherService = mock(DeliveryDispatcherService.class);
    private final FallbackDeliveryConsumer consumer = new FallbackDeliveryConsumer(
            envelopeParser,
            deliveryDispatcherService,
            new SchedulerDeliveryProperties()
    );

    @Test
    void shouldDelegateParsedFallbackEventToDispatcher() {
        var envelope = fallbackEnvelope();
        when(envelopeParser.parse("raw")).thenReturn(envelope);

        consumer.onMessage("raw", Map.of("topic", "delivery.fallback"));

        verify(deliveryDispatcherService).handleFallback(envelope);
    }

    @Test
    void shouldPropagateParserErrorAndAvoidDispatcherCall() {
        when(envelopeParser.parse("broken")).thenThrow(new IllegalArgumentException("bad fallback"));

        assertThatThrownBy(() -> consumer.onMessage("broken", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad fallback");

        verifyNoInteractions(deliveryDispatcherService);
    }

    private static KafkaEnvelope fallbackEnvelope() {
        return new KafkaEnvelope(
                2L,
                "delivery_fallback",
                "dispatch-1:recipient-1:CHANNEL_EMAIL",
                "DeliveryFallbackRequested",
                Map.of(
                        "dispatch_id", "dispatch-1",
                        "recipient_id", "recipient-1",
                        "previous_channels", java.util.List.of("CHANNEL_EMAIL")
                ),
                Map.of("message_id", "message-2"),
                OffsetDateTime.now()
        );
    }
}
