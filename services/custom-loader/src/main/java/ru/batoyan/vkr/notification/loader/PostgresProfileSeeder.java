package ru.batoyan.vkr.notification.loader;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.IntStream;
import ru.notification.common.proto.v1.Channel;

public final class PostgresProfileSeeder implements RecipientProfileSeeder {

    private static final String UPSERT_PROFILE_SQL = """
            insert into recipient_profile(recipient_id, active, preferred_channel, created_at, updated_at)
            values (?, ?, ?, ?, ?)
            on conflict (recipient_id) do update
               set active = excluded.active,
                   preferred_channel = excluded.preferred_channel,
                   updated_at = excluded.updated_at
            """;

    private static final String UPSERT_CHANNEL_SQL = """
            insert into recipient_channel_consent(
                recipient_id, channel, tenant_key, enabled, blacklisted, destination, created_at, updated_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (recipient_id, channel, tenant_key) do update
               set enabled = excluded.enabled,
                   blacklisted = excluded.blacklisted,
                   destination = excluded.destination,
                   updated_at = excluded.updated_at
            """;

    private final LoaderConfig config;
    private final Random random = new Random();

    public PostgresProfileSeeder(LoaderConfig config) {
        this.config = config;
    }

    @Override
    public void seed() {
        try (var connection = DriverManager.getConnection(
                config.postgresUrl(),
                config.postgresUsername(),
                config.postgresPassword()
        )) {
            connection.setAutoCommit(false);
            try (
                    PreparedStatement profileStatement = connection.prepareStatement(UPSERT_PROFILE_SQL);
                    PreparedStatement channelStatement = connection.prepareStatement(UPSERT_CHANNEL_SQL)
            ) {
                for (var i = 1; i <= config.users(); i++) {
                    var recipientId = recipientId(i);
                    var profile = profile(recipientId, i);
                    bindProfile(profileStatement, profile);
                    profileStatement.addBatch();

                    for (var consent : profile.consents()) {
                        bindConsent(channelStatement, profile.recipientId(), consent, profile.updatedAt());
                        channelStatement.addBatch();
                    }
                }

                profileStatement.executeBatch();
                channelStatement.executeBatch();
                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to seed recipient profiles into PostgreSQL", ex);
        }
    }

    @Override
    public List<String> recipientIds() {
        return IntStream.rangeClosed(1, config.users())
                .mapToObj(this::recipientId)
                .toList();
    }

    private void bindProfile(PreparedStatement statement, SeedProfile profile) throws SQLException {
        statement.setString(1, profile.recipientId());
        statement.setBoolean(2, profile.active());
        statement.setString(3, profile.preferredChannel().name());
        statement.setTimestamp(4, Timestamp.from(profile.updatedAt()));
        statement.setTimestamp(5, Timestamp.from(profile.updatedAt()));
    }

    private void bindConsent(PreparedStatement statement, String recipientId, SeedConsent consent, Instant updatedAt) throws SQLException {
        statement.setString(1, recipientId);
        statement.setString(2, consent.channel().name());
        statement.setString(3, consent.tenant());
        statement.setBoolean(4, consent.enabled());
        statement.setBoolean(5, consent.blacklisted());
        statement.setString(6, consent.destination());
        statement.setTimestamp(7, Timestamp.from(updatedAt));
        statement.setTimestamp(8, Timestamp.from(updatedAt));
    }

    private String recipientId(int index) {
        return config.recipientPrefix() + String.format(Locale.ROOT, "%05d", index);
    }

    private SeedProfile profile(String recipientId, int index) {
        var active = random.nextDouble() >= 0.03d;
        var updatedAt = Instant.now().minusSeconds(random.nextInt(3600));
        var preferred = choosePreferredChannel();
        var tenant = "tenant-" + (1 + index % 20);

        var globalEmailBlacklisted = random.nextDouble() < 0.04d;
        var globalSmsBlacklisted = random.nextDouble() < 0.07d;
        var globalPushBlacklisted = random.nextDouble() < 0.02d;

        var consents = List.of(
                new SeedConsent(Channel.CHANNEL_EMAIL, "", true, globalEmailBlacklisted, recipientId + "@example.test"),
                new SeedConsent(Channel.CHANNEL_SMS, "", true, globalSmsBlacklisted, "79" + String.format(Locale.ROOT, "%09d", index)),
                new SeedConsent(Channel.CHANNEL_PUSH, "", true, globalPushBlacklisted, "device-" + recipientId),
                new SeedConsent(Channel.CHANNEL_EMAIL, tenant, true, random.nextDouble() < 0.08d, recipientId + "+" + tenant + "@example.test"),
                new SeedConsent(Channel.CHANNEL_SMS, tenant, true, random.nextDouble() < 0.1d, "78" + String.format(Locale.ROOT, "%09d", index))
        );
        return new SeedProfile(recipientId, active, preferred, updatedAt, consents);
    }

    private Channel choosePreferredChannel() {
        var pick = random.nextDouble();
        if (pick < config.emailShare()) {
            return Channel.CHANNEL_EMAIL;
        }
        if (pick < config.emailShare() + config.smsShare()) {
            return Channel.CHANNEL_SMS;
        }
        return Channel.CHANNEL_PUSH;
    }

    private record SeedProfile(
            String recipientId,
            boolean active,
            Channel preferredChannel,
            Instant updatedAt,
            List<SeedConsent> consents
    ) {
    }

    private record SeedConsent(
            Channel channel,
            String tenant,
            boolean enabled,
            boolean blacklisted,
            String destination
    ) {
    }
}
