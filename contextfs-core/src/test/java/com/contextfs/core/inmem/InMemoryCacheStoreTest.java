package com.contextfs.core.inmem;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryCacheStore")
class InMemoryCacheStoreTest {

    private final InMemoryCacheStore cache = new InMemoryCacheStore();

    @Test
    @DisplayName("put/get 正常读写，未设置 TTL 则不过期")
    void putGetWithoutTtl() {
        cache.put("ctxfs:read:a:L0", "v1", null);
        assertThat(cache.get("ctxfs:read:a:L0")).contains("v1");
    }

    @Test
    @DisplayName("TTL 已过期后读取返回空")
    void expiredEntryReturnsEmpty() {
        cache.put("key", "value", Duration.ofMillis(-1));
        assertThat(cache.get("key")).isEmpty();
    }

    @Test
    @DisplayName("重复 put 覆盖旧值")
    void overwriteValue() {
        cache.put("key", "v1", null);
        cache.put("key", "v2", null);
        assertThat(cache.get("key")).contains("v2");
    }

    @Test
    @DisplayName("evict 删除指定 key")
    void evictSingleKey() {
        cache.put("key", "value", null);
        cache.evict("key");
        assertThat(cache.get("key")).isEmpty();
    }

    @Test
    @DisplayName("evictByPrefix 仅删除匹配前缀的 key")
    void evictByPrefixOnlyMatchesPrefix() {
        cache.put("ctxfs:read:a:L0", "1", null);
        cache.put("ctxfs:read:a:L1", "2", null);
        cache.put("ctxfs:read:b:L0", "3", null);
        cache.put("other", "4", null);

        cache.evictByPrefix("ctxfs:read:a:");

        assertThat(cache.get("ctxfs:read:a:L0")).isEmpty();
        assertThat(cache.get("ctxfs:read:a:L1")).isEmpty();
        assertThat(cache.get("ctxfs:read:b:L0")).contains("3");
        assertThat(cache.get("other")).contains("4");
    }
}
