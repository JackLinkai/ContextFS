package com.contextfs.storage.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * RedisCacheStore 集成测试：Testcontainers Redis 7。
 *
 * <p>仅在 {@code integration} profile 下由 failsafe 执行：{@code mvn verify -Pintegration}（需 Docker）。本机无
 * Docker 时跳过。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("RedisCacheStore 集成测试（Redis）")
class RedisCacheStoreIT {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7").withExposedPorts(6379);

    private LettuceConnectionFactory factory;
    private RedisCacheStore cache;

    @BeforeAll
    void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        cache = new RedisCacheStore(new StringRedisTemplate(factory));
    }

    @AfterAll
    void tearDown() {
        factory.destroy();
    }

    @Test
    @DisplayName("put/get 往返一致，缺失 key 返回 empty")
    void putAndGet() {
        cache.put("ctxfs:user/10001:L0", "{\"content\":\"summary\"}", null);

        assertThat(cache.get("ctxfs:user/10001:L0")).hasValue("{\"content\":\"summary\"}");
        assertThat(cache.get("ctxfs:missing")).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("重复 put 覆盖旧值")
    void putOverwrites() {
        cache.put("key:overwrite", "v1", null);
        cache.put("key:overwrite", "v2", null);

        assertThat(cache.get("key:overwrite")).hasValue("v2");
    }

    @Test
    @DisplayName("带 TTL 的 key 到期后自动失效")
    void ttlExpiry() {
        cache.put("key:ttl", "value", Duration.ofMillis(300));

        assertThat(cache.get("key:ttl")).isPresent();
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> cache.get("key:ttl").isEmpty());
        assertThat(cache.get("key:ttl")).isEmpty();
    }

    @Test
    @DisplayName("evict 删除指定 key")
    void evictSingle() {
        cache.put("key:evict", "value", null);

        cache.evict("key:evict");

        assertThat(cache.get("key:evict")).isEmpty();
    }

    @Test
    @DisplayName("evictByPrefix 仅删除匹配前缀的 key")
    void evictByPrefix() {
        cache.put("ctxfs:user/10001:L0", "a", null);
        cache.put("ctxfs:user/10001:L1", "b", null);
        cache.put("ctxfs:user/20002:L0", "c", null);
        cache.put("other:keep", "d", null);

        cache.evictByPrefix("ctxfs:user/10001:");

        assertThat(cache.get("ctxfs:user/10001:L0")).isEmpty();
        assertThat(cache.get("ctxfs:user/10001:L1")).isEmpty();
        assertThat(cache.get("ctxfs:user/20002:L0")).isPresent();
        assertThat(cache.get("other:keep")).isPresent();
    }
}
