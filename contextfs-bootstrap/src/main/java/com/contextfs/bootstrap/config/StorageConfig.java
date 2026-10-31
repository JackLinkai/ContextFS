package com.contextfs.bootstrap.config;

import com.contextfs.core.port.CacheStore;
import com.contextfs.core.port.EmbeddingClient;
import com.contextfs.core.port.LlmClient;
import com.contextfs.core.port.NodeRepository;
import com.contextfs.storage.llm.LangChain4jEmbeddingClient;
import com.contextfs.storage.llm.LangChain4jLlmClient;
import com.contextfs.storage.milvus.MilvusVectorStore;
import com.contextfs.storage.pg.PgNodeRepository;
import com.contextfs.storage.redis.RedisCacheStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** 存储适配装配：PostgreSQL 节点仓储、Redis 缓存与条件启用的 Milvus / LLM / Embedding 适配。 */
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

    /** LLM 客户端适配（OpenAI 兼容接口），仅在 {@code contextfs.llm.enabled=true} 时装配。 */
    @Bean
    @ConditionalOnProperty(name = "contextfs.llm.enabled", havingValue = "true")
    public LlmClient llmClient(
            @Value("${contextfs.llm.base-url}") String baseUrl,
            @Value("${contextfs.llm.api-key}") String apiKey,
            @Value("${contextfs.llm.model}") String model,
            @Value("${contextfs.llm.timeout}") Duration timeout) {
        return new LangChain4jLlmClient(baseUrl, apiKey, model, timeout);
    }

    /** 嵌入模型适配（OpenAI 兼容接口），仅在 {@code contextfs.embedding.enabled=true} 时装配。 */
    @Bean
    @ConditionalOnProperty(name = "contextfs.embedding.enabled", havingValue = "true")
    public EmbeddingClient embeddingClient(
            @Value("${contextfs.embedding.base-url}") String baseUrl,
            @Value("${contextfs.embedding.api-key}") String apiKey,
            @Value("${contextfs.embedding.model}") String model) {
        return new LangChain4jEmbeddingClient(baseUrl, apiKey, model);
    }
}
