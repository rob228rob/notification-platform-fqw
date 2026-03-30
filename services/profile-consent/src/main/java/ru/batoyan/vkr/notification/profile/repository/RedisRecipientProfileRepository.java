package ru.batoyan.vkr.notification.profile.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import ru.batoyan.vkr.notification.profile.config.ProfileConsentRedisProperties;
import ru.batoyan.vkr.notification.profile.model.ChannelConsent;
import ru.batoyan.vkr.notification.profile.model.RecipientProfile;
import ru.notification.common.proto.v1.Channel;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RedisRecipientProfileRepository implements RecipientProfileRepository {

    private final StringRedisTemplate redisTemplate;
    private final ProfileConsentRedisProperties properties;

    @Override
    public Optional<RecipientProfile> findByRecipientId(String recipientId) {
        var values = redisTemplate.opsForHash().entries(key(recipientId));
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(mapProfile(recipientId, values));
    }

    @Override
    public Map<String, RecipientProfile> findAllByRecipientIds(Collection<String> recipientIds) {
        var result = new LinkedHashMap<String, RecipientProfile>();
        for (var recipientId : recipientIds) {
            findByRecipientId(recipientId).ifPresent(profile -> result.put(recipientId, profile));
        }
        return result;
    }

    private String key(String recipientId) {
        return properties.getKeyPrefix() + recipientId;
    }

    private RecipientProfile mapProfile(String recipientId, Map<Object, Object> rawValues) {
        var values = new LinkedHashMap<String, String>();
        rawValues.forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));

        var channels = new EnumMap<Channel, ChannelConsent>(Channel.class);
        channels.put(Channel.CHANNEL_EMAIL, buildChannel(Channel.CHANNEL_EMAIL, values, "email"));
        channels.put(Channel.CHANNEL_SMS, buildChannel(Channel.CHANNEL_SMS, values, "sms"));
        channels.put(Channel.CHANNEL_PUSH, buildChannel(Channel.CHANNEL_PUSH, values, "push"));

        return new RecipientProfile(
                recipientId,
                parseBoolean(values.getOrDefault("active", "true")),
                parseChannel(values.getOrDefault("preferred_channel", "CHANNEL_EMAIL")),
                Map.copyOf(channels),
                parseInstant(values.get("updated_at"))
        );
    }

    private ChannelConsent buildChannel(Channel channel, Map<String, String> values, String prefix) {
        return new ChannelConsent(
                channel,
                parseBoolean(values.getOrDefault(prefix + ".enabled", "false")),
                parseBoolean(values.getOrDefault(prefix + ".blacklisted", "false")),
                values.getOrDefault(prefix + ".destination", "")
        );
    }

    private Channel parseChannel(String value) {
        try {
            return Channel.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return Channel.CHANNEL_UNSPECIFIED;
        }
    }

    private boolean parseBoolean(String value) {
        return List.of("1", "true", "TRUE", "yes", "YES", "on", "ON").contains(value);
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            return Instant.EPOCH;
        }
    }
}
