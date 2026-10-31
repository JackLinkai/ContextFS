package com.contextfs.memory.pipeline;

import com.contextfs.core.model.ContextNode;
import com.contextfs.core.model.Level;
import com.contextfs.core.model.NodeLevel;
import com.contextfs.core.model.NodeType;
import com.contextfs.core.port.EmbeddingClient;
import com.contextfs.core.port.NodeRepository;
import com.contextfs.core.port.VectorRecord;
import com.contextfs.core.port.VectorStore;
import com.contextfs.core.uri.CtxfsUri;
import com.contextfs.core.util.Tokens;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Session 记忆管线：会话提交后归档为 DOCUMENT 节点，异步提取偏好 / 经验 / 事实三类记忆， 与已有 MEMORY 节点按词法相似度做新建 / 合并 /
 * 跳过决策，并刷新三层内容与向量。
 *
 * <p>记忆写入调用方对应的目标 scope：userId 优先 → {@code user/{userId}}，其次 agentId → {@code
 * agent/{agentId}}，均缺省写入 {@code global}；会话归档固定写入 {@code session/{sessionId}}，实现四级 scope
 * 隔离。提取与合并决策以结构化日志输出， 阶段 7 接入 retrieval_log / tool_call_log 后落库。
 *
 * <p>{@code contextfs.memory.enabled=false} 时仅归档不提取；{@code async=true} 时提取在
 * 线程池中异步执行。EmbeddingClient / VectorStore 缺省时跳过向量化，不影响主流程。
 */
@Service
public class MemoryPipelineService {

    private static final Logger log = LoggerFactory.getLogger(MemoryPipelineService.class);

    /** memories 目录名，记忆节点挂载在 {@code ctxfs://{scope}/memories} 下。 */
    public static final String MEMORIES_DIR = "memories";

    /** 合并决策阈值：相似度不低于该值时合并进已有记忆。 */
    static final double MERGE_THRESHOLD = 0.65;

    /** 跳过决策阈值：相似度不低于该值视为已被已有记忆覆盖。 */
    static final double SKIP_THRESHOLD = 0.35;

    /** L0 摘要截断长度（字符）。 */
    static final int L0_MAX_CHARS = 100;

    /** 同步执行器标记：executor 为该实例时表示不经过线程池。 */
    public static final Executor SYNC_EXECUTOR = Runnable::run;

    private final NodeRepository nodeRepository;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final MemoryExtractor extractor;
    private final boolean enabled;
    private final Executor executor;

    @Autowired
    public MemoryPipelineService(
            NodeRepository nodeRepository,
            ObjectProvider<EmbeddingClient> embeddingProvider,
            ObjectProvider<VectorStore> vectorProvider,
            MemoryExtractor extractor,
            @Value("${contextfs.memory.enabled:true}") boolean enabled,
            @Value("${contextfs.memory.async:true}") boolean async) {
        this(
                nodeRepository,
                embeddingProvider.getIfAvailable(),
                vectorProvider.getIfAvailable(),
                extractor,
                enabled,
                async ? defaultExecutor() : SYNC_EXECUTOR);
    }

    /** 测试与手动装配入口：依赖直传，嵌入与向量可空。 */
    public MemoryPipelineService(
            NodeRepository nodeRepository,
            EmbeddingClient embeddingClient,
            VectorStore vectorStore,
            MemoryExtractor extractor,
            boolean enabled,
            Executor executor) {
        this.nodeRepository = Objects.requireNonNull(nodeRepository, "nodeRepository");
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.enabled = enabled;
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** 提取是否在异步线程池中执行。 */
    public boolean isAsync() {
        return executor != SYNC_EXECUTOR;
    }

    /**
     * 提交会话：归档消息并在启用时提取记忆。
     *
     * @param sessionId 会话 ID，归档到 {@code ctxfs://session/{sessionId}/archive.md}
     * @param agentId Agent ID，缺省记忆目标 scope 时使用
     * @param userId 用户 ID，记忆目标 scope 的最高优先级
     * @param messages 会话消息
     */
    public CommitOutcome commit(
            String sessionId, String agentId, String userId, List<SessionMessage> messages) {
        Objects.requireNonNull(sessionId, "sessionId");
        List<SessionMessage> safeMessages = messages == null ? List.of() : List.copyOf(messages);
        // 1. 归档：会话消息落库为 DOCUMENT 节点
        CtxfsUri archiveUri = CtxfsUri.of("session/" + sessionId, "archive.md");
        writeNode(archiveUri, NodeType.DOCUMENT, renderArchive(safeMessages));
        if (!enabled) {
            log.info("记忆管线未启用，仅归档: uri={}", archiveUri);
            return new CommitOutcome(archiveUri.toString(), List.of());
        }
        // 2. 提取
        List<MemoryCandidate> candidates = extractor.extract(safeMessages);
        // 3. 决策与写入
        String targetScope = targetScope(agentId, userId);
        List<MemoryChange> changes = new ArrayList<>();
        for (MemoryCandidate candidate : candidates) {
            changes.add(mergeOrCreate(targetScope, candidate));
        }
        return new CommitOutcome(archiveUri.toString(), List.copyOf(changes));
    }

    /** 异步提交：async=true 时经线程池执行，调用方立即返回。 */
    public void commitAsync(
            String sessionId, String agentId, String userId, List<SessionMessage> messages) {
        executor.execute(() -> commit(sessionId, agentId, userId, messages));
    }

    /** 对单条候选记忆做合并决策：与目标 scope 下已有 MEMORY 节点比较词法相似度。 */
    private MemoryChange mergeOrCreate(String targetScope, MemoryCandidate candidate) {
        List<ContextNode> existingMemories =
                nodeRepository.listChildren(CtxfsUri.of(targetScope, MEMORIES_DIR)).stream()
                        .filter(node -> node.type() == NodeType.MEMORY)
                        .toList();
        ContextNode best = null;
        double bestSimilarity = 0;
        for (ContextNode node : existingMemories) {
            String content =
                    nodeRepository
                            .findLevel(node.uri(), Level.L2)
                            .map(NodeLevel::content)
                            .orElse("");
            double similarity = lexicalSimilarity(candidate.content(), content);
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                best = node;
            }
        }
        if (best != null) {
            String existing =
                    nodeRepository
                            .findLevel(best.uri(), Level.L2)
                            .map(NodeLevel::content)
                            .orElse("");
            if (bestSimilarity >= MERGE_THRESHOLD) {
                if (existing.contains(candidate.content())) {
                    log.info(
                            "记忆决策: uri={}, type={}, decision=SKIPPED, similarity={}, 原因=内容重复",
                            best.uri(),
                            candidate.type(),
                            String.format(Locale.ROOT, "%.2f", bestSimilarity));
                    return new MemoryChange(
                            best.uri().toString(),
                            candidate.type(),
                            MergeDecision.SKIPPED,
                            bestSimilarity);
                }
                String merged = existing + "\n- " + candidate.content();
                writeNode(best.uri(), NodeType.MEMORY, merged);
                log.info(
                        "记忆决策: uri={}, type={}, decision=MERGED, similarity={}",
                        best.uri(),
                        candidate.type(),
                        String.format(Locale.ROOT, "%.2f", bestSimilarity));
                return new MemoryChange(
                        best.uri().toString(),
                        candidate.type(),
                        MergeDecision.MERGED,
                        bestSimilarity);
            }
            if (bestSimilarity >= SKIP_THRESHOLD) {
                log.info(
                        "记忆决策: uri={}, type={}, decision=SKIPPED, similarity={}, 原因=已被覆盖",
                        best.uri(),
                        candidate.type(),
                        String.format(Locale.ROOT, "%.2f", bestSimilarity));
                return new MemoryChange(
                        best.uri().toString(),
                        candidate.type(),
                        MergeDecision.SKIPPED,
                        bestSimilarity);
            }
        }
        CtxfsUri created = createMemoryUri(targetScope, candidate.type(), existingMemories);
        writeNode(created, NodeType.MEMORY, candidate.content());
        log.info(
                "记忆决策: uri={}, type={}, decision=CREATED, similarity={}",
                created,
                candidate.type(),
                String.format(Locale.ROOT, "%.2f", bestSimilarity));
        return new MemoryChange(
                created.toString(), candidate.type(), MergeDecision.CREATED, bestSimilarity);
    }

    /** 新记忆节点 URI：{type 小写}-{同类型序号}.md。 */
    private CtxfsUri createMemoryUri(
            String targetScope, MemoryType type, List<ContextNode> existingMemories) {
        String prefix = type.name().toLowerCase(Locale.ROOT) + "-";
        long count =
                existingMemories.stream()
                        .filter(node -> node.uri().name().startsWith(prefix))
                        .count();
        return CtxfsUri.of(targetScope, MEMORIES_DIR + "/" + prefix + (count + 1) + ".md");
    }

    /** 记忆目标 scope：userId 优先，其次 agentId，均缺省为 global。 */
    private String targetScope(String agentId, String userId) {
        if (userId != null && !userId.isBlank()) {
            return "user/" + userId;
        }
        if (agentId != null && !agentId.isBlank()) {
            return "agent/" + agentId;
        }
        return "global";
    }

    /** 归档内容：逐行 {@code role: content}。 */
    private String renderArchive(List<SessionMessage> messages) {
        StringBuilder sb = new StringBuilder("# 会话归档\n");
        for (SessionMessage message : messages) {
            sb.append(message.role()).append(": ").append(message.content()).append('\n');
        }
        return sb.toString();
    }

    /** 写入节点并刷新三层内容与向量：L0 为截断摘要，L1 / L2 为全文。 */
    private void writeNode(CtxfsUri uri, NodeType type, String content) {
        Instant now = Instant.now();
        ensureAncestors(uri, now);
        ContextNode existing = nodeRepository.findByUri(uri).orElse(null);
        ContextNode node =
                existing != null
                        ? new ContextNode(
                                uri,
                                type,
                                uri.parent().orElse(null),
                                existing.metadata(),
                                existing.createdAt(),
                                now)
                        : ContextNode.create(uri, type, now);
        nodeRepository.save(node);
        nodeRepository.saveLevel(uri, new NodeLevel(Level.L2, content, Tokens.estimate(content)));
        nodeRepository.saveLevel(uri, new NodeLevel(Level.L1, content, Tokens.estimate(content)));
        String l0 = content.length() > L0_MAX_CHARS ? content.substring(0, L0_MAX_CHARS) : content;
        nodeRepository.saveLevel(uri, new NodeLevel(Level.L0, l0, Tokens.estimate(l0)));
        upsertVectorQuietly(uri, l0);
    }

    /** 确保祖先目录存在（mkdir -p 语义），含 scope 根节点。 */
    private void ensureAncestors(CtxfsUri uri, Instant now) {
        List<CtxfsUri> missing = new ArrayList<>();
        var parent = uri.parent();
        while (parent.isPresent() && !nodeRepository.exists(parent.get())) {
            missing.add(parent.get());
            parent = parent.get().parent();
        }
        for (int i = missing.size() - 1; i >= 0; i--) {
            nodeRepository.save(ContextNode.create(missing.get(i), NodeType.DIRECTORY, now));
        }
    }

    /** 向量化 L0 内容；嵌入或向量库缺省 / 失败时静默跳过。 */
    private void upsertVectorQuietly(CtxfsUri uri, String l0) {
        if (embeddingClient == null || vectorStore == null) {
            return;
        }
        try {
            vectorStore.upsert(new VectorRecord(uri, Level.L0, l0, embeddingClient.embed(l0)));
        } catch (RuntimeException e) {
            log.debug("记忆向量化跳过: uri={}, error={}", uri, e.getMessage());
        }
    }

    /** 词法相似度：字符 bigram 的 Jaccard 系数（0-1），后续可替换为向量余弦相似度。 */
    static double lexicalSimilarity(String a, String b) {
        if (a == null || b == null || a.length() < 2 || b.length() < 2) {
            return 0;
        }
        java.util.Set<String> gramsA = bigrams(a);
        java.util.Set<String> gramsB = bigrams(b);
        int intersection = 0;
        for (String gram : gramsA) {
            if (gramsB.contains(gram)) {
                intersection++;
            }
        }
        int union = gramsA.size() + gramsB.size() - intersection;
        return union == 0 ? 0 : (double) intersection / union;
    }

    private static java.util.Set<String> bigrams(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        java.util.Set<String> grams = new java.util.HashSet<>();
        for (int i = 0; i < normalized.length() - 1; i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }

    private static Executor defaultExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("memory-pipeline-", 0).factory());
    }
}
