package ru.batoyan.vkr.notification.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.batoyan.vkr.notification.profile.model.ChannelConsent;
import ru.batoyan.vkr.notification.profile.model.RecipientProfileDomain;
import ru.batoyan.vkr.notification.profile.repository.RecipientProfileRepository;
import ru.notification.common.proto.v1.Channel;
import ru.notification.profile.proto.v1.CHECK_REASON_CODE;

class RecipientProfileServiceTest {

    @Test
    void shouldAllowEmailWhenProfileIsActiveAndChannelEnabled() {
        var service = new RecipientProfileService(repositoryWith(activeProfile()));

        var result = service.checkRecipientChannel("recipient-1", Channel.CHANNEL_EMAIL, "tenant-a");

        assertThat(result.allowed()).isTrue();
        assertThat(result.destination()).isEqualTo("user@example.test");
        assertThat(result.reasonCode()).isEqualTo(CHECK_REASON_CODE.ALLOWED);
    }

    @Test
    void shouldRejectInactiveProfile() {
        var service = new RecipientProfileService(repositoryWith(activeProfile(false)));

        var result = service.checkRecipientChannel("recipient-1", Channel.CHANNEL_EMAIL, "tenant-a");

        assertThat(result.allowed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo(CHECK_REASON_CODE.PROFILE_INACTIVE);
    }

    @Test
    void shouldRejectDisabledChannel() {
        var service = new RecipientProfileService(repositoryWith(profileWith(Channel.CHANNEL_EMAIL,
                new ChannelConsent(Channel.CHANNEL_EMAIL, "tenant-a", false, false, "user@example.test"))));

        var result = service.checkRecipientChannel("recipient-1", Channel.CHANNEL_EMAIL, "tenant-a");

        assertThat(result.allowed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo(CHECK_REASON_CODE.CHANNEL_DISABLED);
    }

    @Test
    void shouldRejectBlacklistedChannel() {
        var service = new RecipientProfileService(repositoryWith(profileWith(Channel.CHANNEL_EMAIL,
                new ChannelConsent(Channel.CHANNEL_EMAIL, "tenant-a", true, true, "user@example.test"))));

        var result = service.checkRecipientChannel("recipient-1", Channel.CHANNEL_EMAIL, "tenant-a");

        assertThat(result.allowed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo(CHECK_REASON_CODE.CHANNEL_BLACKLISTED);
    }

    @Test
    void shouldRejectMissingDestination() {
        var service = new RecipientProfileService(repositoryWith(profileWith(Channel.CHANNEL_EMAIL,
                new ChannelConsent(Channel.CHANNEL_EMAIL, "tenant-a", true, false, " "))));

        var result = service.checkRecipientChannel("recipient-1", Channel.CHANNEL_EMAIL, "tenant-a");

        assertThat(result.allowed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo(CHECK_REASON_CODE.DESTINATION_MISSING);
    }

    @Test
    void shouldRejectUnknownRecipient() {
        var service = new RecipientProfileService(repositoryWith(null));

        var result = service.checkRecipientChannel("missing", Channel.CHANNEL_EMAIL, "tenant-a");

        assertThat(result.allowed()).isFalse();
        assertThat(result.destination()).isEmpty();
        assertThat(result.reasonCode()).isEqualTo(CHECK_REASON_CODE.PROFILE_NOT_FOUND);
    }

    @Test
    void shouldEvaluateEmailAndSmsIndependently() {
        var service = new RecipientProfileService(repositoryWith(activeProfile()));

        var email = service.checkRecipientChannel("recipient-1", Channel.CHANNEL_EMAIL, "tenant-a");
        var sms = service.checkRecipientChannel("recipient-1", Channel.CHANNEL_SMS, "tenant-a");

        assertThat(email.allowed()).isTrue();
        assertThat(sms.allowed()).isTrue();
        assertThat(email.destination()).isEqualTo("user@example.test");
        assertThat(sms.destination()).isEqualTo("+10000000000");
    }

    @Test
    void shouldNotMutateProfileDuringCheck() {
        var profile = activeProfile();
        var service = new RecipientProfileService(repositoryWith(profile));

        service.checkRecipientChannel("recipient-1", Channel.CHANNEL_EMAIL, "tenant-a");

        assertThat(profile.channels()).containsKey(Channel.CHANNEL_EMAIL);
        assertThat(profile.channel(Channel.CHANNEL_EMAIL).destination()).isEqualTo("user@example.test");
    }

    @Test
    void shouldPropagateRepositoryException() {
        var service = new RecipientProfileService(new FailingRepository());

        assertThatThrownBy(() -> service.checkRecipientChannel("recipient-1", Channel.CHANNEL_EMAIL, "tenant-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("profile storage unavailable");
    }

    @ParameterizedTest
    @CsvSource({
            "true,CHANNEL_EMAIL,true,false,user@example.test,true,ALLOWED,user@example.test",
            "true,CHANNEL_SMS,true,false,+10000000000,true,ALLOWED,+10000000000",
            "true,CHANNEL_EMAIL,false,false,user@example.test,false,CHANNEL_DISABLED,user@example.test",
            "true,CHANNEL_SMS,false,false,+10000000000,false,CHANNEL_DISABLED,+10000000000",
            "true,CHANNEL_EMAIL,true,true,user@example.test,false,CHANNEL_BLACKLISTED,user@example.test",
            "true,CHANNEL_SMS,true,true,+10000000000,false,CHANNEL_BLACKLISTED,+10000000000",
            "true,CHANNEL_EMAIL,true,false,'',false,DESTINATION_MISSING,''",
            "true,CHANNEL_SMS,true,false,'',false,DESTINATION_MISSING,''",
            "true,CHANNEL_EMAIL,true,false,' ',false,DESTINATION_MISSING,''",
            "true,CHANNEL_SMS,true,false,' ',false,DESTINATION_MISSING,''",
            "false,CHANNEL_EMAIL,true,false,user@example.test,false,PROFILE_INACTIVE,''",
            "false,CHANNEL_SMS,true,false,+10000000000,false,PROFILE_INACTIVE,''",
            "true,CHANNEL_EMAIL,false,true,user@example.test,false,CHANNEL_DISABLED,user@example.test",
            "true,CHANNEL_SMS,false,true,+10000000000,false,CHANNEL_DISABLED,+10000000000",
            "true,CHANNEL_EMAIL,true,false,user2@example.test,true,ALLOWED,user2@example.test",
            "true,CHANNEL_SMS,true,false,+19999999999,true,ALLOWED,+19999999999",
            "true,CHANNEL_EMAIL,true,false,recipient@example.test,true,ALLOWED,recipient@example.test",
            "true,CHANNEL_SMS,true,false,+15555555555,true,ALLOWED,+15555555555",
            "true,CHANNEL_EMAIL,false,false,'',false,CHANNEL_DISABLED,''",
            "true,CHANNEL_SMS,false,false,'',false,CHANNEL_DISABLED,''"
    })
    void shouldEvaluateChannelConsentMatrix(
            boolean active,
            Channel channel,
            boolean enabled,
            boolean blacklisted,
            String destination,
            boolean expectedAllowed,
            CHECK_REASON_CODE expectedReason,
            String expectedDestination
    ) {
        var consent = new ChannelConsent(channel, "tenant-a", enabled, blacklisted, destination);
        var service = new RecipientProfileService(repositoryWith(profileWith(active, channel, consent)));

        var result = service.checkRecipientChannel("recipient-1", channel, "tenant-a");

        assertThat(result.allowed()).isEqualTo(expectedAllowed);
        assertThat(result.reasonCode()).isEqualTo(expectedReason);
        assertThat(result.destination()).isEqualTo(expectedDestination);
    }

    private static RecipientProfileDomain activeProfile() {
        return activeProfile(true);
    }

    private static RecipientProfileDomain activeProfile(boolean active) {
        var channels = new LinkedHashMap<Channel, ChannelConsent>();
        channels.put(Channel.CHANNEL_EMAIL,
                new ChannelConsent(Channel.CHANNEL_EMAIL, "tenant-a", true, false, "user@example.test"));
        channels.put(Channel.CHANNEL_SMS,
                new ChannelConsent(Channel.CHANNEL_SMS, "tenant-a", true, false, "+10000000000"));
        return new RecipientProfileDomain("recipient-1", active, Channel.CHANNEL_EMAIL, channels, Instant.now());
    }

    private static RecipientProfileDomain profileWith(Channel channel, ChannelConsent consent) {
        return new RecipientProfileDomain("recipient-1", true, Channel.CHANNEL_EMAIL, Map.of(channel, consent), Instant.now());
    }

    private static RecipientProfileDomain profileWith(boolean active, Channel channel, ChannelConsent consent) {
        return new RecipientProfileDomain("recipient-1", active, Channel.CHANNEL_EMAIL, Map.of(channel, consent), Instant.now());
    }

    private static RecipientProfileRepository repositoryWith(RecipientProfileDomain profile) {
        return new RecipientProfileRepository() {
            @Override
            public Optional<RecipientProfileDomain> findByRecipientId(String recipientId, String tenant) {
                return Optional.ofNullable(profile);
            }

            @Override
            public Map<String, RecipientProfileDomain> findAllByRecipientIds(Collection<String> recipientIds, String tenant) {
                return profile == null ? Map.of() : Map.of(profile.recipientId(), profile);
            }
        };
    }

    private static final class FailingRepository implements RecipientProfileRepository {
        @Override
        public Optional<RecipientProfileDomain> findByRecipientId(String recipientId, String tenant) {
            throw new IllegalStateException("profile storage unavailable");
        }

        @Override
        public Map<String, RecipientProfileDomain> findAllByRecipientIds(Collection<String> recipientIds, String tenant) {
            throw new IllegalStateException("profile storage unavailable");
        }
    }
}
