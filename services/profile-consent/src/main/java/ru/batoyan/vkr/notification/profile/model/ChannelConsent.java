package ru.batoyan.vkr.notification.profile.model;

import ru.notification.common.proto.v1.Channel;

public record ChannelConsent(
        Channel channel,
        boolean enabled,
        boolean blacklisted,
        String destination
) {

    public boolean allowed() {
        return enabled && !blacklisted && destination != null && !destination.isBlank();
    }
}
