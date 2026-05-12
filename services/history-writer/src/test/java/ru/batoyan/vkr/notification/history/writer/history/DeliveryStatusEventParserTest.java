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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    @ParameterizedTest
    @CsvSource({
            "MAIL_DELIVERY_STATUS_SENT,true",
            "MAIL_DELIVERY_STATUS_FAILED,true",
            "MAIL_DELIVERY_STATUS_CANCELED,true",
            "MAIL_DELIVERY_STATUS_SKIPPED,true",
            "SMS_DELIVERY_STATUS_SENT,true",
            "SMS_DELIVERY_STATUS_FAILED,true",
            "SMS_DELIVERY_STATUS_CANCELED,true",
            "SMS_DELIVERY_STATUS_SKIPPED,true",
            "DELIVERY_STATUS_SENT,true",
            "DELIVERY_STATUS_FAILED,true",
            "DELIVERY_STATUS_CANCELED,true",
            "DELIVERY_STATUS_SKIPPED,true",
            "MAIL_DELIVERY_STATUS_RETRY,false",
            "SMS_DELIVERY_STATUS_RETRY,false",
            "DELIVERY_STATUS_RETRY,false",
            "MAIL_DELIVERY_STATUS_PENDING,false",
            "SMS_DELIVERY_STATUS_PENDING,false",
            "DELIVERY_STATUS_PENDING,false",
            "UNKNOWN,false",
            "'',false"
    })
    void clickHouseRecordShouldClassifyFinalStatuses(String status, boolean expectedFinal) {
        var event = new DeliveryStatusEvent(
                10L,
                "delivery",
                "delivery-10",
                "DeliveryStatusChanged",
                Map.of("status", status),
                Map.of(),
                OffsetDateTime.parse("2026-05-12T10:00:00Z"),
                "topic",
                0,
                1L
        );

        var record = ClickHouseDeliveryHistoryRecord.fromEvent(event, new ObjectMapper());

        assertThat(record.isFinal()).isEqualTo(expectedFinal);
    }

    @ParameterizedTest
    @CsvSource({
            "email,user@example.test,user@example.test",
            "phone,+10000000000,+10000000000",
            "destination,recipient@example.test,recipient@example.test",
            "provider,mailgun,mailgun",
            "reason_code,TIMEOUT,TIMEOUT",
            "error_code,NETWORK,NETWORK",
            "error_message,provider timeout,provider timeout",
            "reason_message,blocked by policy,blocked by policy",
            "template_id,welcome-template,welcome-template",
            "correlation_id,corr-1,corr-1",
            "correlationId,corr-2,corr-2",
            "idempotency_key,idem-1,idem-1",
            "provider_message_id,provider-1,provider-1",
            "channel,CHANNEL_EMAIL,CHANNEL_EMAIL",
            "previous_status,MAIL_DELIVERY_STATUS_RETRY,MAIL_DELIVERY_STATUS_RETRY"
    })
    void clickHouseRecordShouldKeepPayloadAliasesInSerializedPayload(String field, String value, String expected) {
        var event = new DeliveryStatusEvent(
                11L,
                "delivery",
                "delivery-11",
                "DeliveryStatusChanged",
                Map.of(field, value, "status", "MAIL_DELIVERY_STATUS_SENT"),
                Map.of(),
                OffsetDateTime.parse("2026-05-12T10:00:00Z"),
                "topic",
                0,
                1L
        );

        var record = ClickHouseDeliveryHistoryRecord.fromEvent(event, new ObjectMapper());

        assertThat(record.payloadJson()).contains(expected);
        assertThat(record.payloadHash()).hasSize(64);
    }

    @ParameterizedTest
    @CsvSource({
            "attempt_no,1,1",
            "attempt_no,2,2",
            "attempt_no,3,3",
            "max_attempts,1,1",
            "max_attempts,5,5",
            "template_version,1,1",
            "template_version,12,12",
            "priority,0,0",
            "priority,10,10",
            "latency_ms,123,123"
    })
    void clickHouseRecordShouldMapNumericPayloadFields(String field, String value, long expected) {
        var event = new DeliveryStatusEvent(
                12L,
                "delivery",
                "delivery-12",
                "DeliveryStatusChanged",
                Map.of(field, value, "status", "MAIL_DELIVERY_STATUS_SENT"),
                Map.of(),
                OffsetDateTime.parse("2026-05-12T10:00:00Z"),
                "topic",
                0,
                1L
        );

        var record = ClickHouseDeliveryHistoryRecord.fromEvent(event, new ObjectMapper());

        switch (field) {
            case "attempt_no" -> assertThat(record.attemptNo()).isEqualTo((int) expected);
            case "max_attempts" -> assertThat(record.maxAttempts()).isEqualTo((int) expected);
            case "template_version" -> assertThat(record.templateVersion()).isEqualTo((int) expected);
            case "priority" -> assertThat(record.priority()).isEqualTo((int) expected);
            case "latency_ms" -> assertThat(record.latencyMs()).isEqualTo(expected);
            default -> throw new IllegalArgumentException(field);
        }
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
