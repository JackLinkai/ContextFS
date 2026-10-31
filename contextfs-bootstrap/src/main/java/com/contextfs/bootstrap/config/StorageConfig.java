package com.contextfs.bootstrap.config;

import com.contextfs.core.port.CacheStore;
import com.contextfs.core.port.NodeRepository;
import com.contextfs.storage.milvus.MilvusVectorStore;
import com.contextfs.storage.pg.PgNodeRepository;
import com.contextfs.storage.redis.RedisCacheStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** 存储适配装配：PostgreSQL 节点仓储、Redis 缓存与条件启用的 Milvus 向量存储。 */
@Configuration
public class StorageConfig {

    /** PostgreSQL 节点仓储适配（JdbcTemplate 与 ObjectMapper 由 Spring Boot 自动装配）。 */
    @Bean
    public NodeRepository nodeRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new PgNodeRepository(jdbcTemplate, objectMapper);
    }

    /** Redis 缓存适配（StringRedisTemplate 由 Spring Boot 自动装配，连接为惰性建立）。 */
    @Bean
    public CacheStore cacheStore(StringRedisTemplate redisTemplate) {
        return new RedisCacheStore(redisTemplate);
    }

    /**
     * Milvus 向量存储适配：构造即建立 gRPC 连接，仅在 {@code contextfs.storage.milvus.enabled=true} 时装配；阶段 4
     * 接入向量化后再默认启用。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "contextfs.storage.milvus.enabled", havingValue = "true")
    public MilvusVectorStore milvusVectorStore(
            @Value("${contextfs.storage.milvus.host}") String host,
            @Value("${contextfs.storage.milvus.port}") int port,
            @Value("${contextfs.storage.milvus.dimension}") int dimension) {
        return new MilvusVectorStore("http://" + host + ":" + port, dimension);
    }
}
