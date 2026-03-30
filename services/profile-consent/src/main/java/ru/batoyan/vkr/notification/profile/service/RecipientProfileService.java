package ru.batoyan.vkr.notification.profile.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.batoyan.vkr.notification.profile.model.RecipientProfile;
import ru.batoyan.vkr.notification.profile.repository.RecipientProfileRepository;
import ru.notification.common.proto.v1.Channel;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RecipientProfileService {

    private final RecipientProfileRepository repository;

    public Optional<RecipientProfile> getRecipientProfile(String recipientId) {
        return repository.findByRecipientId(recipientId);
    }

    public Map<String, RecipientProfile> getRecipientProfiles(Collection<String> recipientIds) {
        return repository.findAllByRecipientIds(recipientIds);
    }

    public ChannelCheckResult checkRecipientChannel(String recipientId, Channel channel) {
        return repository.findByRecipientId(recipientId)
                .map(profile -> checkByChannels(profile, channel))
                // default negative path
                .orElseGet(() -> new ChannelCheckResult(false, "", Channel.CHANNEL_UNSPECIFIED, "PROFILE_NOT_FOUND"));
    }

    // rule checks for possibility of dispatching
    private ChannelCheckResult checkByChannels(RecipientProfile profile, Channel channel) {
        if (!profile.active()) {
            return new ChannelCheckResult(false, "", profile.preferredChannel(), "PROFILE_INACTIVE");
        }

        var channelConsent = profile.channel(channel);
        if (!channelConsent.enabled()) {
            return new ChannelCheckResult(false, channelConsent.destination(), profile.preferredChannel(), "CHANNEL_DISABLED");
        }
        if (channelConsent.blacklisted()) {
            return new ChannelCheckResult(false, channelConsent.destination(), profile.preferredChannel(), "CHANNEL_BLACKLISTED");
        }
        if (channelConsent.destination() == null || channelConsent.destination().isBlank()) {
            return new ChannelCheckResult(false, "", profile.preferredChannel(), "DESTINATION_MISSING");
        }
        return new ChannelCheckResult(true, channelConsent.destination(), profile.preferredChannel(), "ALLOWED");
    }

    public record ChannelCheckResult(
            boolean allowed,
            String destination,
            Channel preferredChannel,
            String reasonCode
    ) {
    }
}
