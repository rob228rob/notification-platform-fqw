package ru.batoyan.vkr.notification.history.writer.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class DeliveryStatusEventParserTest {

    private final DeliveryStatusEventParser parser = new DeliveryStatusEventParser(new ObjectMapper());

    @Test
    void shouldParseStatusEventEnvelope() {
        var event = parser.parse(envelopeJson());

        assertThat(event.outboxId()).isEqualTo(42L);
        assertThat(event.aggregateType()).isEqualTo("mail_delivery");
        assertThat(event.aggregateId()).isEqualTo("delivery-1");
        assertThat(event.eventType()).isEqualTo("MailDeliveryStatusChanged");
        assertThat(event.payload()).containsEntry("status", "MAIL_DELIVERY_STATUS_SENT");
        assertThat(event.headers()).containsEntry("message_id", "delivery-1");
    }

    @Test
    void shouldUnwrapTextualKafkaPayload() throws Exception {
        var mapper = new ObjectMapper();
        var wrapped = mapper.writeValueAsString(envelopeJson());

        var event = parser.parse(wrapped);

        assertThat(event.outboxId()).isEqualTo(42L);
        assertThat(event.payload()).containsEntry("recipient_id", "recipient-1");
    }

    @Test
    void shouldRejectMalformedJson() {
        assertThatThrownBy(() -> parser.parse("{broken"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to parse delivery status event");
    }

    @Test
    void shouldAttachKafkaMetadata() {
        var event = parser.parse(envelopeJson())
                .withKafkaMetadata("notification.mail.delivery-statuses", 2, 99L);

        assertThat(event.kafkaTopic()).isEqualTo("notification.mail.delivery-statuses");
        assertThat(event.kafkaPartition()).isEqualTo(2);
        assertThat(event.kafkaOffset()).isEqualTo(99L);
    }

    @Test
    void consumerShouldStoreParsedEventWithKafkaMetadata() {
        var eventParser = mock(DeliveryStatusEventParser.class);
        var store = mock(DeliveryHistoryStore.class);
        var consumer = new DeliveryHistoryConsumer(eventParser, store);
        var parsed = new DeliveryStatusEvent(1L, "mail_delivery", "delivery-1", "changed",
                Map.of(), Map.of(), OffsetDateTime.parse("2026-05-12T10:00:00Z"), "", -1, -1);
        when(eventParser.parse("payload")).thenReturn(parsed);
        when(store.save(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        consumer.onMessage(new ConsumerRecord<>("topic-a", 3, 10L, "key", "payload"));

        verify(store).save(org.mockito.ArgumentMatchers.argThat(event ->
                event.kafkaTopic().equals("topic-a")
                        && event.kafkaPartition() == 3
                        && event.kafkaOffset() == 10L
        ));
    }

    @Test
    void consumerShouldPropagateParserErrorAndSkipStore() {
        var eventParser = mock(DeliveryStatusEventParser.class);
        var store = mock(DeliveryHistoryStore.class);
        var consumer = new DeliveryHistoryConsumer(eventParser, store);
        when(eventParser.parse("{broken")).thenThrow(new IllegalArgumentException("bad status"));

        assertThatThrownBy(() -> consumer.onMessage(new ConsumerRecord<>("topic-a", 0, 1L, "key", "{broken")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad status");

        verifyNoInteractions(store);
    }

    @Test
    void consumerShouldPropagateStoreErrorAfterSuccessfulParse() {
        var eventParser = mock(DeliveryStatusEventParser.class);
        var store = mock(DeliveryHistoryStore.class);
        var consumer = new DeliveryHistoryConsumer(eventParser, store);
        var parsed = new DeliveryStatusEvent(1L, "mail_delivery", "delivery-1", "changed",
                Map.of(), Map.of(), OffsetDateTime.parse("2026-05-12T10:00:00Z"), "", -1, -1);
        when(eventParser.parse("payload")).thenReturn(parsed);
        when(store.save(org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("clickhouse down"));

        assertThatThrownBy(() -> consumer.onMessage(new ConsumerRecord<>("topic-a", 0, 1L, "key", "payload")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("clickhouse down");

        verify(store).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void clickHouseRecordShouldMapFailedReason() {
        var event = new DeliveryStatusEvent(
                7L,
                "mail_delivery",
                "delivery-7",
                "MailDeliveryStatusChanged",
                Map.of(
                        "status", "MAIL_DELIVERY_STATUS_FAILED",
                        "error_message", "provider timeout",
                        "attempt_no", "3",
                        "occurred_at", "2026-05-12T10:00:00Z"
                ),
                Map.of(),
                OffsetDateTime.parse("2026-05-12T10:00:00Z"),
                "topic",
                0,
                1L
        );

        var record = ClickHouseDeliveryHistoryRecord.fromEvent(event, new ObjectMapper());

        assertThat(record.status()).isEqualTo("MAIL_DELIVERY_STATUS_FAILED");
        assertThat(record.reasonMessage()).isEqualTo("provider timeout");
        assertThat(record.attemptNo()).isEqualTo(3);
        assertThat(record.isFinal()).isTrue();
    }

    @Test
    void clickHouseRecordShouldTreatRetryAsNonFinal() {
        var event = new DeliveryStatusEvent(
                8L,
                "sms_delivery",
                "delivery-8",
                "SmsDeliveryStatusChanged",
                Map.of("status", "SMS_DELIVERY_STATUS_RETRY"),
                Map.of(),
                OffsetDateTime.parse("2026-05-12T10:00:00Z"),
                "topic",
                0,
                1L
        );

        var record = ClickHouseDeliveryHistoryRecord.fromEvent(event, new ObjectMapper());

        assertThat(record.isFinal()).isFalse();
    }

    private static String envelopeJson() {
        return """
                {
                  "outboxId": 42,
                  "aggregateType": "mail_delivery",
                  "aggregateId": "delivery-1",
                  "eventType": "MailDeliveryStatusChanged",
                  "payload": {
                    "dispatch_id": "dispatch-1",
                    "event_id": "event-1",
                    "recipient_id": "recipient-1",
                    "status": "MAIL_DELIVERY_STATUS_SENT"
                  },
                  "headers": {
                    "message_id": "delivery-1"
                  },
                  "createdAt": "2026-05-12T10:00:00Z"
                }
                """;
    }
}
