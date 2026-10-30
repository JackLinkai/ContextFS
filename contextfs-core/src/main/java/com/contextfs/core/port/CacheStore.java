package com.contextfs.core.port;

import java.time.Duration;
import java.util.Optional;

/** 缓存端口，由 Redis 适配实现。值统一为字符串（如序列化后的 JSON）。 */
public interface CacheStore {

    Optional<String> get(String key);

    /** 写入缓存，ttl 为空表示不过期。 */
    void put(String key, String value, Duration ttl);

    void evict(String key);

    /** 按前缀批量失效。 */
    void evictByPrefix(String prefix);
}
