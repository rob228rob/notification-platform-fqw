package ru.batoyan.vkr.notification.profile.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.batoyan.vkr.notification.profile.model.ChannelConsent;
import ru.batoyan.vkr.notification.profile.model.RecipientProfileDomain;
import ru.notification.common.proto.v1.Channel;

@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.profile-consent.storage", name = "type", havingValue = "postgres", matchIfMissing = true)
public class PostgresRecipientProfileRepository implements RecipientProfileRepository {

    private static final String FIND_ALL_SQL = """
            select p.recipient_id,
                   p.active,
                   p.preferred_channel,
                   p.updated_at,
                   c.channel,
                   c.tenant_key,
                   c.enabled,
                   c.blacklisted,
                   c.destination
              from recipient_profile p
              left join recipient_channel_consent c
                on c.recipient_id = p.recipient_id
               and (
                    :tenant = ''
                    and (c.tenant_key is null or c.tenant_key = '')
                    or :tenant <> ''
                    and (c.tenant_key = :tenant or c.tenant_key is null or c.tenant_key = '')
               )
             where p.recipient_id in (:recipient_ids)
             order by p.recipient_id,
                      c.channel,
                      case when :tenant <> '' and c.tenant_key = :tenant then 0 else 1 end,
                      c.updated_at desc nulls last,
                      c.created_at desc nulls last
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public Optional<RecipientProfileDomain> findByRecipientId(String recipientId, String tenant) {
        return Optional.ofNullable(findAllByRecipientIds(List.of(recipientId), tenant).get(recipientId));
    }

    @Override
    public Map<String, RecipientProfileDomain> findAllByRecipientIds(Collection<String> recipientIds, String tenant) {
        if (recipientIds.isEmpty()) {
            return Map.of();
        }

        var params = new MapSqlParameterSource()
                .addValue("recipient_ids", recipientIds)
                .addValue("tenant", normalizeTenant(tenant));
        var profiles = new LinkedHashMap<String, MutableProfile>();

        jdbcTemplate.query(FIND_ALL_SQL, params, rs -> {
            while (rs.next()) {
                accumulate(profiles, rs);
            }
            return null;
        });
        var result = new LinkedHashMap<String, RecipientProfileDomain>();
        profiles.forEach((recipientId, profile) -> result.put(recipientId, profile.toDomain()));
        return result;
    }

    private void accumulate(Map<String, MutableProfile> profiles, ResultSet rs) throws SQLException {
        var recipientId = rs.getString("recipient_id");
        var profile = profiles.get(recipientId);
        if (profile == null) {
            profile = new MutableProfile(
                    recipientId,
                    rs.getBoolean("active"),
                    parseChannel(rs.getString("preferred_channel")),
                    readInstant(rs, "updated_at")
            );
            profiles.put(recipientId, profile);
        }

        var channelValue = rs.getString("channel");
        if (channelValue == null || channelValue.isBlank()) {
            return;
        }

        var channel = parseChannel(channelValue);
        if (profile.channels.containsKey(channel)) {
            return;
        }

        profile.channels.put(channel, new ChannelConsent(
                channel,
                normalizeTenant(rs.getString("tenant_key")),
                rs.getBoolean("enabled"),
                rs.getBoolean("blacklisted"),
                defaultString(rs.getString("destination"))
        ));
    }

    private Instant readInstant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private Channel parseChannel(String value) {
        if (value == null || value.isBlank()) {
            return Channel.CHANNEL_UNSPECIFIED;
        }
        try {
            return Channel.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return Channel.CHANNEL_UNSPECIFIED;
        }
    }

    private String normalizeTenant(String tenant) {
        return tenant == null ? "" : tenant.trim();
    }

    private static final class MutableProfile {
        private final String recipientId;
        private final boolean active;
        private final Channel preferredChannel;
        private final Instant updatedAt;
        private final Map<Channel, ChannelConsent> channels = new EnumMap<>(Channel.class);

        private MutableProfile(String recipientId, boolean active, Channel preferredChannel, Instant updatedAt) {
            this.recipientId = recipientId;
            this.active = active;
            this.preferredChannel = preferredChannel;
            this.updatedAt = updatedAt;
        }

        private RecipientProfileDomain toDomain() {
            return new RecipientProfileDomain(recipientId, active, preferredChannel, Map.copyOf(channels), updatedAt);
        }
    }
}
