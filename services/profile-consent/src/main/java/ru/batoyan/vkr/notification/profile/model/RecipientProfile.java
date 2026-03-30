package ru.batoyan.vkr.notification.profile.model;

import ru.notification.common.proto.v1.Channel;

import java.time.Instant;
import java.util.Map;

public record RecipientProfile(
        String recipientId,
        boolean active,
        Channel preferredChannel,
        Map<Channel, ChannelConsent> channels,
        Instant updatedAt
) {

    public ChannelConsent channel(Channel channel) {
        return channels.getOrDefault(channel, new ChannelConsent(channel, false, false, ""));
    }
}
