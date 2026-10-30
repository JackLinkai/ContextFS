package com.contextfs.storage.redis;

import com.contextfs.core.port.CacheStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/** {@link CacheStore} 的 Redis 适配实现，基于 StringRedisTemplate。 */
public class RedisCacheStore implements CacheStore {

    private static final int SCAN_BATCH_SIZE = 200;

    private final StringRedisTemplate redisTemplate;

    public RedisCacheStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        if (ttl == null) {
            redisTemplate.opsForValue().set(key, value);
        } else {
            redisTemplate.opsForValue().set(key, value, ttl);
        }
    }

    @Override
    public void evict(String key) {
        redisTemplate.delete(key);
    }

    @Override
    public void evictByPrefix(String prefix) {
        List<String> keys = new ArrayList<>();
        ScanOptions options =
                ScanOptions.scanOptions().match(prefix + "*").count(SCAN_BATCH_SIZE).build();
        // SCAN 渐进式遍历，避免 KEYS 阻塞 Redis
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            cursor.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
