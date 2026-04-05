package ru.batoyan.vkr.notification.loader;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import ru.notification.common.proto.v1.Channel;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public final class RedisProfileSeeder {

    private final LoaderConfig config;
    private final Random random = new Random();

    public RedisProfileSeeder(LoaderConfig config) {
        this.config = config;
    }

    public void seed() {
        var clientConfig = DefaultJedisClientConfig.builder()
                .user(config.redisUser())
                .password(config.redisPassword())
                .timeoutMillis(5_000)
                .build();
        try (var jedis = new JedisPooled(new HostAndPort(config.redisHost(), config.redisPort()), clientConfig)) {
            for (int i = 1; i <= config.users(); i++) {
                String recipientId = recipientId(i);
                jedis.hset(key(recipientId), profile(recipientId, i));
            }
        }
    }

    public List<String> recipientIds() {
        return java.util.stream.IntStream.rangeClosed(1, config.users())
                .mapToObj(this::recipientId)
                .toList();
    }

    private String recipientId(int index) {
        return config.recipientPrefix() + String.format(Locale.ROOT, "%05d", index);
    }

    private String key(String recipientId) {
        return config.redisKeyPrefix() + recipientId;
    }

    private Map<String, String> profile(String recipientId, int index) {
        boolean active = random.nextDouble() >= 0.03d;
        boolean emailBlacklisted = random.nextDouble() < 0.04d;
        boolean smsBlacklisted = random.nextDouble() < 0.07d;
        boolean pushBlacklisted = random.nextDouble() < 0.02d;

        Channel preferred = choosePreferredChannel();

        Map<String, String> values = new HashMap<>();
        values.put("active", Boolean.toString(active));
        values.put("preferred_channel", preferred.name());
        values.put("updated_at", Instant.now().minusSeconds(random.nextInt(3600)).toString());

        values.put("email.enabled", "true");
        values.put("email.blacklisted", Boolean.toString(emailBlacklisted));
        values.put("email.destination", recipientId + "@example.test");

        values.put("sms.enabled", "true");
        values.put("sms.blacklisted", Boolean.toString(smsBlacklisted));
        values.put("sms.destination", "79" + String.format(Locale.ROOT, "%09d", index));

        values.put("push.enabled", "true");
        values.put("push.blacklisted", Boolean.toString(pushBlacklisted));
        values.put("push.destination", "device-" + recipientId);

        return values;
    }

    private Channel choosePreferredChannel() {
        double pick = random.nextDouble();
        if (pick < config.emailShare()) {
            return Channel.CHANNEL_EMAIL;
        }
        if (pick < config.emailShare() + config.smsShare()) {
            return Channel.CHANNEL_SMS;
        }
        return Channel.CHANNEL_PUSH;
    }
}
