package com.contextfs.retrieval.engine;

import com.contextfs.core.model.ContextNode;
import com.contextfs.core.model.Level;
import com.contextfs.core.model.NodeLevel;
import com.contextfs.core.model.NodeType;
import com.contextfs.core.port.EmbeddingClient;
import com.contextfs.core.port.NodeRepository;
import com.contextfs.core.port.VectorMatch;
import com.contextfs.core.port.VectorStore;
import com.contextfs.core.uri.CtxfsUri;
import com.contextfs.core.util.Tokens;
import com.contextfs.observability.log.RetrievalLogEntry;
import com.contextfs.observability.log.TraceLogStore;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 目录递归检索引擎：两阶段检索 + 三层按需加载。
 *
 * <p>SEARCH 模式：查询向量在 L0 目录层做 topK 语义检索，分数阈值剪枝后对命中目录递归下钻 （子层分数按 {@link #DRILL_DECAY} 衰减），最终按
 * maxLevel 从高到低按需加载内容并用 token 预算截断。向量库或嵌入模型不可用时自动降级为词法检索（URI / L0 内容关键词匹配）。
 *
 * <p>FIND 模式：在 scope 子树内按 URI / 名称关键词（分数 1.0）或 L0 内容关键词（分数 0.5） 精确匹配，不依赖向量与嵌入模型。
 */
@Service
public class RetrievalService {

    /** 目录下钻时的分数衰减因子：每下钻一层，子节点分数乘以该值。 */
    static final double DRILL_DECAY = 0.5;

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final NodeRepository nodeRepository;
    private final VectorStore vectorStore;
    private final EmbeddingClient embeddingClient;
    private final RetrievalConfig config;
    private final TraceLogStore traceLogStore;
    private final SecureRandom random = new SecureRandom();

    /** Spring 装配入口：向量库、嵌入模型与链路日志均可能未启用，取不到时降级 / 跳过。 */
    @Autowired
    public RetrievalService(
            NodeRepository nodeRepository,
            ObjectProvider<VectorStore> vectorStoreProvider,
            ObjectProvider<EmbeddingClient> embeddingClientProvider,
            ObjectProvider<TraceLogStore> traceLogStoreProvider,
            @Value("${contextfs.retrieval.top-k-dir:5}") int topKDir,
            @Value("${contextfs.retrieval.max-level:L1}") Level maxLevel,
            @Value("${contextfs.retrieval.score-threshold:0.3}") double scoreThreshold,
            @Value("${contextfs.retrieval.token-budget:4096}") int tokenBudget,
            @Value("${contextfs.retrieval.max-depth:3}") int maxDepth) {
        this(
                nodeRepository,
                vectorStoreProvider.getIfAvailable(),
                embeddingClientProvider.getIfAvailable(),
                new RetrievalConfig(topKDir, maxLevel, scoreThreshold, tokenBudget, maxDepth),
                traceLogStoreProvider.getIfAvailable());
    }

    /** 测试与手动装配入口：依赖直传，可空，不记录检索日志。 */
    public RetrievalService(
            NodeRepository nodeRepository,
            VectorStore vectorStore,
            EmbeddingClient embeddingClient,
            RetrievalConfig config) {
        this(nodeRepository, vectorStore, embeddingClient, config, null);
    }

    /** 测试与手动装配入口：含链路日志，检索完成后写 retrieval_log。 */
    public RetrievalService(
            NodeRepository nodeRepository,
            VectorStore vectorStore,
            EmbeddingClient embeddingClient,
            RetrievalConfig config,
            TraceLogStore traceLogStore) {
        this.nodeRepository = Objects.requireNonNull(nodeRepository, "nodeRepository");
        this.vectorStore = vectorStore;
        this.embeddingClient = embeddingClient;
        this.config = Objects.requireNonNull(config, "config");
        this.traceLogStore = traceLogStore;
    }

    /** 默认配置，供接口层在其基础上做请求级覆盖。 */
    public RetrievalConfig defaultConfig() {
        return config;
    }

    /**
     * 执行检索。
     *
     * @param scope 检索范围 scope，如 {@code global}、{@code user/10001}
     * @param query 查询文本（SEARCH 为语义查询，FIND 为关键词）
     * @param mode 检索模式
     * @param override 请求级配置覆盖，null 时用默认配置
     * @param traceId 链路追踪 ID，空时自动生成
     */
    public RetrievalResult retrieve(
            String scope,
            String query,
            RetrievalMode mode,
            RetrievalConfig override,
            String traceId) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(query, "query");
        RetrievalConfig cfg = override != null ? override : config;
        String trace =
                traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        String spanId = newSpanId();
        Instant startedAt = Instant.now();
        long start = System.nanoTime();
        List<RetrievalItem> items;
        try {
            List<Hit> hits =
                    mode == RetrievalMode.FIND
                            ? findHits(scope, query, cfg)
                            : searchHits(scope, query, cfg);
            items = assemble(hits, cfg);
        } catch (RuntimeException e) {
            appendRetrievalLogQuietly(
                    trace,
                    spanId,
                    scope,
                    query,
                    mode,
                    List.of(),
                    List.of(),
                    startedAt,
                    start,
                    0,
                    "ERROR",
                    e.getMessage());
            throw e;
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        int promptTokens = items.stream().mapToInt(item -> Tokens.estimate(item.content())).sum();
        appendRetrievalLogQuietly(
                trace,
                spanId,
                scope,
                query,
                mode,
                items.stream().map(RetrievalItem::uri).toList(),
                items.stream().map(item -> item.level().name()).toList(),
                startedAt,
                start,
                promptTokens,
                "OK",
                null);
        return new RetrievalResult(
                trace, mode, items, new RetrievalResult.TokenUsage(promptTokens, 0), latencyMs);
    }

    /** 记录 retrieval_log：链路日志缺省 / 写入失败时跳过，不影响检索主流程。 */
    private void appendRetrievalLogQuietly(
            String traceId,
            String spanId,
            String scope,
            String query,
            RetrievalMode mode,
            List<String> retrievedUris,
            List<String> levels,
            Instant startedAt,
            long startNanos,
            int promptTokens,
            String status,
            String error) {
        if (traceLogStore == null) {
            return;
        }
        try {
            traceLogStore.appendRetrievalLog(
                    new RetrievalLogEntry(
                            traceId,
                            spanId,
                            null,
                            null,
                            scope,
                            query,
                            mode.name().toLowerCase(Locale.ROOT),
                            retrievedUris,
                            levels,
                            (System.nanoTime() - startNanos) / 1_000_000,
                            promptTokens,
                            0,
                            status,
                            error,
                            startedAt));
        } catch (RuntimeException e) {
            log.debug("retrieval_log 写入跳过: traceId={}, error={}", traceId, e.getMessage());
        }
    }

    /** 生成 16 位十六进制 spanId。 */
    private String newSpanId() {
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 内部命中项：uri + 分数 + 相对命中目录的下钻深度。 */
    private record Hit(CtxfsUri uri, double score, int depth) {}

    /** SEARCH 主流程：L0 向量 topK → 阈值剪枝 → 目录下钻；依赖缺失时降级词法检索。 */
    private List<Hit> searchHits(String scope, String query, RetrievalConfig cfg) {
        if (vectorStore == null || embeddingClient == null) {
            log.debug("向量检索依赖缺失（vectorStore/embeddingClient 未装配），降级词法检索");
            return findHits(scope, query, cfg);
        }
        float[] queryVector = embeddingClient.embed(query);
        List<VectorMatch> matches = vectorStore.search(queryVector, scope, Level.L0, cfg.topKDir());
        Map<CtxfsUri, Hit> merged = new LinkedHashMap<>();
        for (VectorMatch match : matches) {
            // 分数阈值剪枝：低分命中不再参与下钻
            if (match.score() < cfg.scoreThreshold()) {
                continue;
            }
            merge(merged, new Hit(match.uri(), match.score(), 0));
            if (isDirectory(match.uri())) {
                drillDown(match.uri(), match.score(), 1, cfg, merged);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** 递归下钻命中目录：直接子层分数按层衰减，超过 maxDepth 停止。 */
    private void drillDown(
            CtxfsUri directory,
            double score,
            int depth,
            RetrievalConfig cfg,
            Map<CtxfsUri, Hit> merged) {
        if (depth > cfg.maxDepth()) {
            return;
        }
        for (ContextNode child : nodeRepository.listChildren(directory)) {
            double childScore = score * DRILL_DECAY;
            merge(merged, new Hit(child.uri(), childScore, depth));
            if (child.type() == NodeType.DIRECTORY) {
                drillDown(child.uri(), childScore, depth + 1, cfg, merged);
            }
        }
    }

    /** FIND 主流程：scope 子树内按 URI / 名称或 L0 内容关键词匹配，按分数取 topK。 */
    private List<Hit> findHits(String scope, String query, RetrievalConfig cfg) {
        CtxfsUri root = CtxfsUri.of(scope, "");
        String normalized = query.toLowerCase(Locale.ROOT);
        List<Hit> hits = new ArrayList<>();
        for (ContextNode node : collectSubtree(root)) {
            if (node.uri().equals(root)) {
                continue;
            }
            double score = matchScore(node, normalized);
            if (score > 0) {
                hits.add(new Hit(node.uri(), score, 0));
            }
        }
        hits.sort(
                Comparator.comparingDouble(Hit::score)
                        .reversed()
                        .thenComparing(hit -> hit.uri().toString()));
        return hits.size() > cfg.topKDir() ? new ArrayList<>(hits.subList(0, cfg.topKDir())) : hits;
    }

    /** URI / 名称命中得 1.0，L0 内容命中得 0.5，否则 0。 */
    private double matchScore(ContextNode node, String normalizedKeyword) {
        if (node.uri().toString().toLowerCase(Locale.ROOT).contains(normalizedKeyword)) {
            return 1.0;
        }
        return nodeRepository
                .findLevel(node.uri(), Level.L0)
                .filter(
                        level ->
                                level.content()
                                        .toLowerCase(Locale.ROOT)
                                        .contains(normalizedKeyword))
                .map(level -> 0.5)
                .orElse(0.0);
    }

    /** 收集子树；scope 根节点允许隐式存在，此时逐层补充直接子节点的子树。 */
    private List<ContextNode> collectSubtree(CtxfsUri root) {
        List<ContextNode> subtree = nodeRepository.listSubtree(root, CtxfsUri.MAX_PATH_DEPTH);
        if (subtree.isEmpty() && root.isRoot()) {
            subtree = new ArrayList<>();
            for (ContextNode child : nodeRepository.listChildren(root)) {
                subtree.addAll(
                        nodeRepository.listSubtree(child.uri(), CtxfsUri.MAX_PATH_DEPTH - 1));
            }
        }
        return subtree;
    }

    /** 组装结果：按分数降序、深度浅优先排序，按 maxLevel 降级加载内容并做 token 预算截断。 */
    private List<RetrievalItem> assemble(List<Hit> hits, RetrievalConfig cfg) {
        List<Hit> sorted =
                hits.stream()
                        .sorted(
                                Comparator.comparingDouble(Hit::score)
                                        .reversed()
                                        .thenComparingInt(Hit::depth)
                                        .thenComparing(hit -> hit.uri().toString()))
                        .toList();
        List<RetrievalItem> items = new ArrayList<>();
        int usedTokens = 0;
        for (Hit hit : sorted) {
            Optional<NodeLevel> loaded = loadContent(hit.uri(), cfg.maxLevel());
            if (loaded.isEmpty()) {
                continue;
            }
            NodeLevel level = loaded.get();
            int tokens = Tokens.estimate(level.content());
            // token 预算控制：预算 > 0 时超预算的条目跳过；首条始终收录以保证非空结果
            if (!items.isEmpty()
                    && cfg.tokenBudget() > 0
                    && usedTokens + tokens > cfg.tokenBudget()) {
                continue;
            }
            items.add(
                    new RetrievalItem(
                            hit.uri().toString(), level.level(), level.content(), hit.score()));
            usedTokens += tokens;
        }
        return items;
    }

    /** 按需加载：从 maxLevel 向下降级（如 L2 → L1 → L0），全部缺失时返回 empty。 */
    private Optional<NodeLevel> loadContent(CtxfsUri uri, Level maxLevel) {
        for (Level level : maxLevel.selfAndBelow()) {
            Optional<NodeLevel> found = nodeRepository.findLevel(uri, level);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private void merge(Map<CtxfsUri, Hit> merged, Hit candidate) {
        Hit existing = merged.get(candidate.uri());
        if (existing == null || candidate.score() > existing.score()) {
            merged.put(candidate.uri(), candidate);
        }
    }

    private boolean isDirectory(CtxfsUri uri) {
        return nodeRepository
                .findByUri(uri)
                .map(node -> node.type() == NodeType.DIRECTORY)
                .orElse(false);
    }
}
