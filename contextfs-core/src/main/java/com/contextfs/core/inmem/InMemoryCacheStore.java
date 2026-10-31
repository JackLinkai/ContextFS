package com.contextfs.core.inmem;

import com.contextfs.core.port.CacheStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 基于内存的 {@link CacheStore} 实现：供单元测试、本地开发与集成测试复用，无外部依赖。 过期采用惰性清理：读取时判断到期即删除。 */
public class InMemoryCacheStore implements CacheStore {

    private record Entry(String value, Instant expireAt) {}

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    @Override
    public Optional<String> get(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expireAt() != null && entry.expireAt().isBefore(Instant.now())) {
            store.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Instant expireAt = ttl == null ? null : Instant.now().plus(ttl);
        store.put(key, new Entry(value, expireAt));
    }

    @Override
    public void evict(String key) {
        store.remove(key);
    }

    @Override
    public void evictByPrefix(String prefix) {
        List<String> matched = new ArrayList<>();
        for (String key : store.keySet()) {
            if (key.startsWith(prefix)) {
                matched.add(key);
            }
        }
        matched.forEach(store::remove);
    }

    /** 清空全部缓存，仅供测试与调试使用。 */
    public void clear() {
        store.clear();
    }
}
