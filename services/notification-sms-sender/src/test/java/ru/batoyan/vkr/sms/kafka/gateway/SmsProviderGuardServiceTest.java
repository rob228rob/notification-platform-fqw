package ru.batoyan.vkr.sms.kafka.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.batoyan.vkr.sms.kafka.policy.ProfileConsentClient;
import ru.notification.common.proto.v1.Channel;
import ru.notification.profile.proto.v1.CHECK_REASON_CODE;
import ru.notification.profile.proto.v1.CheckRecipientChannelResponse;

class SmsProviderGuardServiceTest {

    @Test
    void shouldAllowSmsWhenProfileConsentAllowsSmsChannel() {
        var profileClient = mock(ProfileConsentClient.class);
        when(profileClient.checkRecipientChannel("recipient-1", Channel.CHANNEL_SMS))
                .thenReturn(CheckRecipientChannelResponse.newBuilder()
                        .setAllowed(true)
                        .setDestination("+10000000000")
                        .setReasonCode(CHECK_REASON_CODE.ALLOWED)
                        .build());
        var guard = new SmsProviderGuardService(profileClient, Runnable::run);
        var message = smsMessage("delivery-1", "recipient-1", "+10000000000");

        var result = guard.validateBatch(List.of(message));

        assertThat(result.allowedMessages()).containsExactly(message);
        assertThat(result.rejectedDeliveries()).isEmpty();
    }

    @Test
    void shouldRejectSmsWhenProfileConsentDeniesChannel() {
        var profileClient = mock(ProfileConsentClient.class);
        when(profileClient.checkRecipientChannel("recipient-1", Channel.CHANNEL_SMS))
                .thenReturn(CheckRecipientChannelResponse.newBuilder()
                        .setAllowed(false)
                        .setReasonCode(CHECK_REASON_CODE.CHANNEL_DISABLED)
                        .build());
        var guard = new SmsProviderGuardService(profileClient, Runnable::run);

        var result = guard.validateBatch(List.of(smsMessage("delivery-1", "recipient-1", "+10000000000")));

        assertThat(result.allowedMessages()).isEmpty();
        assertThat(result.rejectedDeliveries()).containsEntry("delivery-1", "CHANNEL_DISABLED");
    }

    @Test
    void shouldRejectSmsWhenProfileConsentThrows() {
        var profileClient = mock(ProfileConsentClient.class);
        when(profileClient.checkRecipientChannel("recipient-1", Channel.CHANNEL_SMS))
                .thenThrow(new IllegalStateException("grpc unavailable"));
        var guard = new SmsProviderGuardService(profileClient, Runnable::run);

        var result = guard.validateBatch(List.of(smsMessage("delivery-1", "recipient-1", "+10000000000")));

        assertThat(result.allowedMessages()).isEmpty();
        assertThat(result.rejectedDeliveries()).containsEntry("delivery-1", "PROFILE_CONSENT_UNAVAILABLE");
    }

    @Test
    void shouldHandleEmptyBatchWithoutProfileCheck() {
        var guard = new SmsProviderGuardService(mock(ProfileConsentClient.class), Runnable::run);

        var result = guard.validateBatch(List.of());

        assertThat(result.allowedMessages()).isEmpty();
        assertThat(result.rejectedDeliveries()).isEmpty();
    }

    @Test
    void shouldKeepIndependentDecisionsForMultipleMessages() {
        var profileClient = mock(ProfileConsentClient.class);
        when(profileClient.checkRecipientChannel("recipient-1", Channel.CHANNEL_SMS))
                .thenReturn(CheckRecipientChannelResponse.newBuilder().setAllowed(true).build());
        when(profileClient.checkRecipientChannel("recipient-2", Channel.CHANNEL_SMS))
                .thenReturn(CheckRecipientChannelResponse.newBuilder()
                        .setAllowed(false)
                        .setReasonCode(CHECK_REASON_CODE.CHANNEL_BLACKLISTED)
                        .build());
        var guard = new SmsProviderGuardService(profileClient, Runnable::run);
        var allowed = smsMessage("delivery-1", "recipient-1", "+10000000000");
        var denied = smsMessage("delivery-2", "recipient-2", "+10000000001");

        var result = guard.validateBatch(List.of(allowed, denied));

        assertThat(result.allowedMessages()).containsExactly(allowed);
        assertThat(result.rejectedDeliveries()).containsEntry("delivery-2", "CHANNEL_BLACKLISTED");
    }

    @Test
    void shouldExposeBatchSendSucceededDeliveryIds() {
        var result = new SmsGateway.BatchSendResult(List.of("delivery-1"), Map.of());

        assertThat(result.succeededDeliveryIds()).containsExactly("delivery-1");
        assertThat(result.failedDeliveryErrors()).isEmpty();
    }

    @Test
    void shouldExposeBatchSendFailedDeliveryErrors() {
        var result = new SmsGateway.BatchSendResult(List.of(), Map.of("delivery-1", "timeout"));

        assertThat(result.succeededDeliveryIds()).isEmpty();
        assertThat(result.failedDeliveryErrors()).containsEntry("delivery-1", "timeout");
    }

    @Test
    void shouldPreserveSmsMessageFields() {
        var message = smsMessage("delivery-1", "recipient-1", "+10000000000");

        assertThat(message.deliveryId()).isEqualTo("delivery-1");
        assertThat(message.idempotencyKey()).isEqualTo("delivery-1");
        assertThat(message.recipientId()).isEqualTo("recipient-1");
        assertThat(message.phone()).isEqualTo("+10000000000");
    }

    private static SmsGateway.SmsMessage smsMessage(String deliveryId, String recipientId, String phone) {
        return new SmsGateway.SmsMessage(deliveryId, deliveryId, recipientId, phone, "template-1", 1, "{}");
    }
}
