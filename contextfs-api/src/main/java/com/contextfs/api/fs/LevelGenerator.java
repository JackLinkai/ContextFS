package com.contextfs.api.fs;

import com.contextfs.core.model.ContextNode;
import com.contextfs.core.model.Level;
import com.contextfs.core.model.NodeLevel;
import com.contextfs.core.model.NodeType;
import com.contextfs.core.port.CacheStore;
import com.contextfs.core.port.EmbeddingClient;
import com.contextfs.core.port.LlmClient;
import com.contextfs.core.port.NodeRepository;
import com.contextfs.core.port.VectorRecord;
import com.contextfs.core.port.VectorStore;
import com.contextfs.core.uri.CtxfsUri;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 三层内容生成器：write 时异步生成 L0（~100 token 摘要）与 L1（~2k token 概览），L2 为原文； 目录节点由子节点 L0 归纳聚合生成；三层内容向量化写入
 * Milvus。
 *
 * <p>失败降级：LLM / 向量库不可用时保留占位，read 直读 L2 兜底；下次写入同一节点时自动重试。 LlmClient / EmbeddingClient / VectorStore
 * 均可缺省（未装配时跳过对应步骤）。
 */
@Service
public class LevelGenerator {

    private static final Logger log = LoggerFactory.getLogger(LevelGenerator.class);

    private final NodeRepository nodeRepository;
    private final CacheStore cacheStore;
    private final LlmClient llmClient;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final Executor executor;

    @Autowired
    public LevelGenerator(
            NodeRepository nodeRepository,
            CacheStore cacheStore,
            ObjectProvider<LlmClient> llmProvider,
            ObjectProvider<EmbeddingClient> embeddingProvider,
            ObjectProvider<VectorStore> vectorProvider,
            @Value("${contextfs.levels.async:true}") boolean async) {
        this(
                nodeRepository,
                cacheStore,
                llmProvider.getIfAvailable(),
                embeddingProvider.getIfAvailable(),
                vectorProvider.getIfAvailable(),
                async ? defaultExecutor() : Runnable::run);
    }

    public LevelGenerator(
            NodeRepository nodeRepository,
            CacheStore cacheStore,
            LlmClient llmClient,
            EmbeddingClient embeddingClient,
            VectorStore vectorStore,
            Executor executor) {
        this.nodeRepository = nodeRepository;
        this.cacheStore = cacheStore;
        this.llmClient = llmClient;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.executor = executor;
    }

    /** 写入后触发生成（按配置同步或异步）。 */
    public void generateAsync(CtxfsUri uri, String content, NodeType type) {
        try {
            executor.execute(() -> generateNow(uri, content, type));
        } catch (RuntimeException e) {
            log.warn("Level generation task rejected: uri={}, error={}", uri, e.getMessage());
        }
    }

    /** 同步执行生成：L2 向量 → L0/L1 摘要（目录聚合子节点 L0）→ 自下而上刷新祖先目录摘要。 */
    public void generateNow(CtxfsUri uri, String content, NodeType type) {
        try {
            generateNode(uri, content, type);
            refreshAncestors(uri);
        } catch (RuntimeException e) {
            log.warn(
                    "Level generation failed, placeholders kept, will retry on next write: uri={}, error={}",
                    uri,
                    e.getMessage());
        }
    }

    private void generateNode(CtxfsUri uri, String content, NodeType type) {
        // L2 向量不依赖 LLM，只要嵌入与向量库可用即写入；目录节点没有 L2 原文
        if (type != NodeType.DIRECTORY) {
            upsertVectorQuietly(uri, Level.L2, content);
        }
        if (llmClient == null) {
            return;
        }
        String material = type == NodeType.DIRECTORY ? childrenMaterial(uri) : content;
        if (material == null) {
            return;
        }
        String l0 = llmClient.summarize(material, Level.L0);
        String l1 = llmClient.summarize(material, Level.L1);
        nodeRepository.saveLevel(
                uri, new NodeLevel(Level.L0, l0, FileSystemService.estimateTokens(l0)));
        nodeRepository.saveLevel(
                uri, new NodeLevel(Level.L1, l1, FileSystemService.estimateTokens(l1)));
        upsertVectorQuietly(uri, Level.L0, l0);
        upsertVectorQuietly(uri, Level.L1, l1);
        evictQuietly(FileSystemService.CACHE_READ_PREFIX + uri + ":");
    }

    /** 子节点写入后，祖先目录摘要自下而上刷新（由子节点 L0 归纳）。 */
    private void refreshAncestors(CtxfsUri uri) {
        Optional<CtxfsUri> parent = uri.parent();
        while (parent.isPresent()) {
            CtxfsUri dir = parent.get();
            try {
                if (nodeRepository.exists(dir)) {
                    generateNode(dir, null, NodeType.DIRECTORY);
                }
            } catch (RuntimeException e) {
                log.warn("Ancestor level refresh failed: uri={}, error={}", dir, e.getMessage());
            }
            parent = dir.parent();
        }
    }

    /** 目录聚合素材：每个子节点一行「名称: L0 摘要」。无子节点时返回 null（跳过生成）。 */
    private String childrenMaterial(CtxfsUri dir) {
        List<ContextNode> children = nodeRepository.listChildren(dir);
        if (children.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ContextNode child : children) {
            String childL0 =
                    nodeRepository
                            .findLevel(child.uri(), Level.L0)
                            .map(NodeLevel::content)
                            .orElse("");
            sb.append(child.uri().name()).append(": ").append(childL0).append('\n');
        }
        return sb.toString();
    }

    private void upsertVectorQuietly(CtxfsUri uri, Level level, String content) {
        if (vectorStore == null || embeddingClient == null) {
            return;
        }
        try {
            vectorStore.upsert(
                    new VectorRecord(uri, level, content, embeddingClient.embed(content)));
        } catch (RuntimeException e) {
            log.warn(
                    "Vector upsert skipped: uri={}, level={}, error={}",
                    uri,
                    level,
                    e.getMessage());
        }
    }

    private void evictQuietly(String prefix) {
        try {
            cacheStore.evictByPrefix(prefix);
        } catch (RuntimeException e) {
            log.debug("Cache eviction skipped: prefix={}, error={}", prefix, e.getMessage());
        }
    }

    private static Executor defaultExecutor() {
        AtomicInteger seq = new AtomicInteger();
        return Executors.newSingleThreadExecutor(
                task -> {
                    Thread thread = new Thread(task, "level-generator-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }
}
