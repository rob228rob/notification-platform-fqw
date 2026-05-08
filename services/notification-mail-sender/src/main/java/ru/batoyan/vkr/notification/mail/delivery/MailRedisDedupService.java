package ru.batoyan.vkr.notification.mail.delivery;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MailRedisDedupService {

    private final StringRedisTemplate redisTemplate;

    public boolean tryAcquire(String messageId, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key(messageId), "1", ttl));
    }

    public void release(String messageId) {
        redisTemplate.delete(key(messageId));
    }

    private String key(String messageId) {
        return "mail:dedup:" + messageId;
    }
}
