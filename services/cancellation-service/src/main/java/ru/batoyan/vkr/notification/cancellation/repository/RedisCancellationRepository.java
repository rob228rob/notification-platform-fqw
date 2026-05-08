package ru.batoyan.vkr.notification.cancellation.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import ru.batoyan.vkr.notification.cancellation.config.CancellationRedisProperties;
import ru.batoyan.vkr.notification.cancellation.model.CancellationRecord;

@Repository
@RequiredArgsConstructor
public class RedisCancellationRepository implements CancellationRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CancellationRedisProperties properties;

    @Override
    public CancellationSaveResult save(CancellationRecord record) {
        var key = key(record.dispatchId());
        var payload = write(record);
        var stored = redisTemplate.opsForValue().setIfAbsent(key, payload, properties.getTtl());
        if (Boolean.TRUE.equals(stored)) {
            return new CancellationSaveResult(record, false, expiresAt(key));
        }
        var current = findByDispatchId(record.dispatchId())
                .orElseThrow(() -> new IllegalStateException("Cancellation record disappeared after duplicate save"));
        return new CancellationSaveResult(current.record(), true, current.expiresAt());
    }

    @Override
    public Optional<CancellationLookupResult> findByDispatchId(String dispatchId) {
        var key = key(dispatchId);
        var payload = redisTemplate.opsForValue().get(key);
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new CancellationLookupResult(read(payload), expiresAt(key)));
    }

    private Instant expiresAt(String key) {
        var ttl = redisTemplate.getExpire(key);
        if (ttl == null || ttl < 0) {
            return Instant.now().plus(properties.getTtl());
        }
        return Instant.now().plusSeconds(ttl);
    }

    private String key(String dispatchId) {
        return properties.getKeyPrefix() + dispatchId;
    }

    private String write(CancellationRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize cancellation record", ex);
        }
    }

    private CancellationRecord read(String payload) {
        try {
            return objectMapper.readValue(payload, CancellationRecord.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize cancellation record", ex);
        }
    }
}
