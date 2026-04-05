package ru.batoyan.vkr.notification.profile.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.batoyan.vkr.notification.profile.model.RecipientProfileDomain;
import ru.batoyan.vkr.notification.profile.repository.RecipientProfileRepository;
import ru.notification.common.proto.v1.Channel;
import ru.notification.profile.proto.v1.CHECK_REASON_CODE;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class RecipientProfileService {

    private final RecipientProfileRepository repository;

    public Optional<RecipientProfileDomain> getRecipientProfile(String recipientId) {
        var profile = repository.findByRecipientId(recipientId);
        log.debug("Service getRecipientProfile: recipientId={}, found={}", recipientId, profile.isPresent());
        return profile;
    }

    public Map<String, RecipientProfileDomain> getRecipientProfiles(Collection<String> recipientIds) {
        var profiles = repository.findAllByRecipientIds(recipientIds);
        log.debug("Service getRecipientProfiles: requested={}, found={}", recipientIds.size(), profiles.size());
        return profiles;
    }

    public ChannelCheckResult checkRecipientChannel(String recipientId, Channel channel) {
        log.debug("Service checkRecipientChannel called: recipientId={}, channel={}", recipientId, channel);
        var result = repository.findByRecipientId(recipientId)
                .map(profile -> checkByChannels(profile, channel))
                // default negative path
                .orElseGet(() -> new ChannelCheckResult(false, "", Channel.CHANNEL_UNSPECIFIED, mapReasonCode("PROFILE_NOT_FOUND")));
        log.debug("Service checkRecipientChannel result: recipientId={}, channel={}, allowed={}, reasonCode={}, preferredChannel={}",
                recipientId, channel, result.allowed(), result.reasonCode(), result.preferredChannel());
        return result;
    }

    // rule checks for possibility of dispatching
    private ChannelCheckResult checkByChannels(RecipientProfileDomain profile, Channel channel) {
        if (!profile.active()) {
            return new ChannelCheckResult(false, "", profile.preferredChannel(), mapReasonCode("PROFILE_INACTIVE"));
        }

        var channelConsent = profile.channel(channel);
        if (!channelConsent.enabled()) {
            return new ChannelCheckResult(false, channelConsent.destination(), profile.preferredChannel(), mapReasonCode("CHANNEL_DISABLED"));
        }
        if (channelConsent.blacklisted()) {
            return new ChannelCheckResult(false, channelConsent.destination(), profile.preferredChannel(), mapReasonCode("CHANNEL_BLACKLISTED"));
        }
        if (channelConsent.destination() == null || channelConsent.destination().isBlank()) {
            return new ChannelCheckResult(false, "", profile.preferredChannel(), mapReasonCode("DESTINATION_MISSING"));
        }
        return new ChannelCheckResult(true, channelConsent.destination(), profile.preferredChannel(), mapReasonCode("ALLOWED"));
    }

    private CHECK_REASON_CODE mapReasonCode(String reasonCode) {
        return switch (reasonCode) {
            case "ALLOWED" -> CHECK_REASON_CODE.ALLOWED;
            case "PROFILE_NOT_FOUND" -> CHECK_REASON_CODE.PROFILE_NOT_FOUND;
            case "PROFILE_INACTIVE" -> CHECK_REASON_CODE.PROFILE_INACTIVE;
            case "CHANNEL_DISABLED" -> CHECK_REASON_CODE.CHANNEL_DISABLED;
            case "CHANNEL_BLACKLISTED" -> CHECK_REASON_CODE.CHANNEL_BLACKLISTED;
            case "DESTINATION_MISSING" -> CHECK_REASON_CODE.DESTINATION_MISSING;
            case "SMS_CHANNEL_MISSING" -> CHECK_REASON_CODE.SMS_CHANNEL_MISSING;
            case "PROFILE_CONSENT_UNAVAILABLE" -> CHECK_REASON_CODE.PROFILE_CONSENT_UNAVAILABLE;
            default -> CHECK_REASON_CODE.CHECK_REASON_CODE_UNSPECIFIED;
        };
    }

    public record ChannelCheckResult(
            boolean allowed,
            String destination,
            Channel preferredChannel,
            CHECK_REASON_CODE reasonCode
    ) {
    }
}
