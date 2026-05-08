package ru.batoyan.vkr.notification.history.writer.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickHouseDeliveryHistoryRecordTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldMapKnownPayloadFieldsAndKafkaMetadata() {
        var event = new DeliveryStatusEvent(
                42L,
                "MAIL_DELIVERY",
                "delivery-42",
                "MailDeliveryStatusChanged",
                Map.ofEntries(
                        Map.entry("event_id", "event-1"),
                        Map.entry("dispatch_id", "dispatch-1"),
                        Map.entry("client_id", "client-1"),
                        Map.entry("recipient_id", "recipient-1"),
                        Map.entry("email", "user@example.com"),
                        Map.entry("channel", "CHANNEL_EMAIL"),
                        Map.entry("status", "MAIL_DELIVERY_STATUS_SENT"),
                        Map.entry("template_id", "template-1"),
                        Map.entry("template_version", String.valueOf(3)),
                        Map.entry("idempotency_key", "idem-1"),
                        Map.entry("attempt_no", String.valueOf(2)),
                        Map.entry("occurred_at", "2026-05-03T12:30:00Z")
                ),
                Map.of(
                        "message_id", "msg-1"
                ),
                OffsetDateTime.parse("2026-05-03T12:29:59Z"),
                "notification.delivery-statuses",
                3,
                128L
        );

        var record = ClickHouseDeliveryHistoryRecord.fromEvent(event, objectMapper);

        assertEquals("client-1", record.clientId());
        assertEquals("event-1", record.eventId());
        assertEquals("dispatch-1", record.dispatchId());
        assertEquals("recipient-1", record.recipientId());
        assertEquals("user@example.com", record.destination());
        assertEquals("CHANNEL_EMAIL", record.channel());
        assertEquals("MAIL_DELIVERY_STATUS_SENT", record.status());
        assertEquals("template-1", record.templateId());
        assertEquals(3, record.templateVersion());
        assertEquals("idem-1", record.idempotencyKey());
        assertEquals("notification.delivery-statuses", record.kafkaTopic());
        assertEquals(3, record.kafkaPartition());
        assertEquals(128L, record.kafkaOffset());
        assertTrue(record.isFinal());
        assertFalse(record.payloadHash().isBlank());
        assertNotNull(record.metadataJson());
    }

    @Test
    void shouldUsePhoneAsDestinationForSmsEvents() {
        var event = new DeliveryStatusEvent(
                7L,
                "SMS_DELIVERY",
                "delivery-7",
                "SmsDeliveryStatusChanged",
                Map.of(
                        "dispatch_id", "dispatch-7",
                        "event_id", "event-7",
                        "client_id", "client-7",
                        "recipient_id", "recipient-7",
                        "phone", "+79990001122",
                        "channel", "CHANNEL_SMS",
                        "status", "SMS_DELIVERY_STATUS_RETRY",
                        "attempt_no", 1
                ),
                Map.of(),
                OffsetDateTime.parse("2026-05-03T11:00:00Z"),
                "notification.delivery-statuses",
                0,
                1L
        );

        var record = ClickHouseDeliveryHistoryRecord.fromEvent(event, objectMapper);

        assertEquals("+79990001122", record.destination());
        assertEquals("CHANNEL_SMS", record.channel());
        assertEquals("SMS_DELIVERY_STATUS_RETRY", record.status());
        assertFalse(record.isFinal());
    }
}
