package ru.batoyan.vkr.notification.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
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
