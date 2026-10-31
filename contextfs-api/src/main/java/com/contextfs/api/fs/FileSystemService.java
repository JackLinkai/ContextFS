package com.contextfs.api.fs;

import com.contextfs.api.dto.NodeDto;
import com.contextfs.api.dto.ReadResultDto;
import com.contextfs.api.dto.TreeNodeDto;
import com.contextfs.api.dto.WriteResultDto;
import com.contextfs.core.exception.NodeNotFoundException;
import com.contextfs.core.exception.ScopeViolationException;
import com.contextfs.core.model.ContextNode;
import com.contextfs.core.model.Level;
import com.contextfs.core.model.NodeLevel;
import com.contextfs.core.model.NodeType;
import com.contextfs.core.port.CacheStore;
import com.contextfs.core.port.NodeRepository;
import com.contextfs.core.uri.CtxfsUri;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 虚拟文件系统应用服务：实现 POSIX 风格的 ls / tree / read / write / find 操作。
 *
 * <p>write 保存 L2 原文并触发三层生成（L0 / L1 摘要 + 向量化，见 {@link LevelGenerator}）； read 按层返回：已生成的层级返回真实内容，未生成的
 * L0 / L1 返回占位文本，LLM 不可用时 L2 直读兜底。 缓存接入读路径，写路径主动失效；所有缓存操作失败时降级，不影响主流程。
 */
@Service
public class FileSystemService {

    public static final String CACHE_READ_PREFIX = "ctxfs:read:";

    /** 缓存中的层级内容载荷。 */
    record CachedLevel(String content, int tokenCount) {}

    private static final Logger log = LoggerFactory.getLogger(FileSystemService.class);
    private static final int MAX_TREE_DEPTH = 16;
    private static final int FIND_RESULT_LIMIT = 100;

    private final NodeRepository nodeRepository;
    private final CacheStore cacheStore;
    private final ObjectMapper objectMapper;
    private final Duration cacheTtl;
    private final LevelGenerator levelGenerator;

    public FileSystemService(
            NodeRepository nodeRepository,
            CacheStore cacheStore,
            ObjectMapper objectMapper,
            @Value("${contextfs.cache.read-ttl:PT10M}") Duration cacheTtl,
            LevelGenerator levelGenerator) {
        this.nodeRepository = Objects.requireNonNull(nodeRepository, "nodeRepository");
        this.cacheStore = Objects.requireNonNull(cacheStore, "cacheStore");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl");
        this.levelGenerator = Objects.requireNonNull(levelGenerator, "levelGenerator");
    }

    /** 列出直接子节点（ls）。scope 根节点允许隐式存在，其余节点必须已创建。 */
    public List<NodeDto> ls(CtxfsUri uri, String allowedScope) {
        checkScope(uri, allowedScope);
        requireNodeUnlessRoot(uri);
        return nodeRepository.listChildren(uri).stream().map(NodeDto::from).toList();
    }

    /** 返回以 uri 为根的嵌套目录树（tree），maxDepth=0 仅返回根节点。 */
    public TreeNodeDto tree(CtxfsUri uri, int maxDepth, String allowedScope) {
        checkScope(uri, allowedScope);
        int depth = Math.min(Math.max(maxDepth, 0), MAX_TREE_DEPTH);
        List<ContextNode> flat = nodeRepository.listSubtree(uri, depth);
        ContextNode root;
        if (flat.isEmpty()) {
            if (!uri.isRoot()) {
                throw new NodeNotFoundException(uri);
            }
            // scope 根节点允许隐式存在，此时仓储无法递归展开，需逐层补充子树
            root = ContextNode.create(uri, NodeType.DIRECTORY, Instant.now());
            flat = new ArrayList<>();
            flat.add(root);
            if (depth > 0) {
                for (ContextNode child : nodeRepository.listChildren(uri)) {
                    flat.addAll(nodeRepository.listSubtree(child.uri(), depth - 1));
                }
            }
        } else {
            root = flat.get(0);
        }
        return buildTree(flat, root);
    }

    /**
     * 读取节点某一层内容（read）。
     *
     * <p>L2 为完整原文；L0 / L1 已生成时返回真实摘要，未生成时返回占位文本（generated=false）。
     */
    public ReadResultDto read(CtxfsUri uri, Level level, String allowedScope) {
        checkScope(uri, allowedScope);
        if (!nodeRepository.exists(uri)) {
            throw new NodeNotFoundException(uri);
        }
        String cacheKey = CACHE_READ_PREFIX + uri + ":" + level;
        Optional<CachedLevel> cached = getFromCache(cacheKey);
        if (cached.isPresent()) {
            return new ReadResultDto(
                    uri.toString(), level, cached.get().content(), cached.get().tokenCount(), true);
        }
        return nodeRepository
                .findLevel(uri, level)
                .map(
                        nodeLevel -> {
                            putToCache(
                                    cacheKey,
                                    new CachedLevel(nodeLevel.content(), nodeLevel.tokenCount()));
                            return new ReadResultDto(
                                    uri.toString(),
                                    level,
                                    nodeLevel.content(),
                                    nodeLevel.tokenCount(),
                                    true);
                        })
                .orElseGet(
                        () -> {
                            if (level == Level.L2) {
                                throw new NodeNotFoundException(
                                        "Level " + level + " not found for node: " + uri);
                            }
                            String placeholder = placeholder(level);
                            return new ReadResultDto(
                                    uri.toString(),
                                    level,
                                    placeholder,
                                    estimateTokens(placeholder),
                                    false);
                        });
    }

    /**
     * 写入或覆盖节点内容（write），内容保存为 L2。自动创建缺失的父目录（mkdir -p 语义）； 未指定类型时沿用已有节点类型，否则默认 DOCUMENT；提供 metadata
     * 时整体覆盖。
     *
     * @param generateLevels 是否触发 L0 / L1 摘要生成与向量化（异步）
     */
    public WriteResultDto write(
            CtxfsUri uri,
            String content,
            NodeType type,
            Map<String, String> metadata,
            boolean generateLevels,
            String allowedScope) {
        checkScope(uri, allowedScope);
        Objects.requireNonNull(content, "content");
        if (uri.isRoot()) {
            throw new IllegalArgumentException("Cannot write content to scope root: " + uri);
        }
        Instant now = Instant.now();
        ensureAncestors(uri, now);
        ContextNode existing = nodeRepository.findByUri(uri).orElse(null);
        NodeType nodeType =
                type != null ? type : existing != null ? existing.type() : NodeType.DOCUMENT;
        Map<String, String> mergedMetadata =
                metadata != null ? metadata : existing != null ? existing.metadata() : Map.of();
        ContextNode node =
                existing != null
                        ? new ContextNode(
                                uri,
                                nodeType,
                                uri.parent().orElse(null),
                                mergedMetadata,
                                existing.createdAt(),
                                now)
                        : new ContextNode(
                                uri, nodeType, uri.parent().orElse(null), mergedMetadata, now, now);
        nodeRepository.save(node);
        nodeRepository.saveLevel(uri, new NodeLevel(Level.L2, content, estimateTokens(content)));
        // 内容变更后该节点全部层级缓存失效
        evictQuietly(CACHE_READ_PREFIX + uri + ":");
        if (generateLevels) {
            levelGenerator.generateAsync(uri, content, nodeType);
        }
        return new WriteResultDto(uri.toString(), nodeType, now);
    }

    /** 在 root 子树内查找节点（find）：按 URI 关键词（大小写不敏感）、节点类型与元数据过滤。 */
    public List<NodeDto> find(
            CtxfsUri root,
            String keyword,
            NodeType type,
            String metadataKey,
            String metadataValue,
            String allowedScope) {
        checkScope(root, allowedScope);
        List<ContextNode> subtree = nodeRepository.listSubtree(root, CtxfsUri.MAX_PATH_DEPTH);
        if (subtree.isEmpty() && !root.isRoot()) {
            throw new NodeNotFoundException(root);
        }
        if (root.isRoot() && subtree.isEmpty()) {
            // scope 根节点允许隐式存在，逐层补充子树
            subtree = new ArrayList<>();
            for (ContextNode child : nodeRepository.listChildren(root)) {
                subtree.addAll(
                        nodeRepository.listSubtree(child.uri(), CtxfsUri.MAX_PATH_DEPTH - 1));
            }
        }
        String normalizedKeyword =
                keyword == null || keyword.isBlank() ? null : keyword.toLowerCase(Locale.ROOT);
        List<NodeDto> result = new ArrayList<>();
        for (ContextNode node : subtree) {
            if (type != null && node.type() != type) {
                continue;
            }
            if (!matchesKeyword(node, normalizedKeyword)) {
                continue;
            }
            if (!matchesMetadata(node, metadataKey, metadataValue)) {
                continue;
            }
            result.add(NodeDto.from(node));
            if (result.size() >= FIND_RESULT_LIMIT) {
                break;
            }
        }
        return result;
    }

    /** L0 / L1 摘要占位文本，阶段 4 接入 LLM 后替换为真实摘要。 */
    static String placeholder(Level level) {
        return "【占位】" + level + " 摘要尚未生成，将在三层内容生成阶段由 LLM 生成。";
    }

    /** 粗略 token 估算：CJK 字符按 1 token，其余按每 4 字符 1 token。阶段 4 换成真实分词器。 */
    static int estimateTokens(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }

    private void requireNodeUnlessRoot(CtxfsUri uri) {
        if (!uri.isRoot() && !nodeRepository.exists(uri)) {
            throw new NodeNotFoundException(uri);
        }
    }

    private void checkScope(CtxfsUri uri, String allowedScope) {
        if (allowedScope == null || allowedScope.isBlank()) {
            return;
        }
        if (!uri.scope().equals(allowedScope)) {
            throw new ScopeViolationException(
                    "Access to scope '"
                            + uri.scope()
                            + "' is not allowed for caller scope '"
                            + allowedScope
                            + "'");
        }
    }

    private void ensureAncestors(CtxfsUri uri, Instant now) {
        List<CtxfsUri> missing = new ArrayList<>();
        Optional<CtxfsUri> parent = uri.parent();
        while (parent.isPresent() && !nodeRepository.exists(parent.get())) {
            missing.add(parent.get());
            parent = parent.get().parent();
        }
        // 由高到低创建，保证父子顺序
        for (int i = missing.size() - 1; i >= 0; i--) {
            nodeRepository.save(ContextNode.create(missing.get(i), NodeType.DIRECTORY, now));
        }
    }

    private boolean matchesKeyword(ContextNode node, String normalizedKeyword) {
        if (normalizedKeyword == null) {
            return true;
        }
        return node.uri().toString().toLowerCase(Locale.ROOT).contains(normalizedKeyword);
    }

    private boolean matchesMetadata(ContextNode node, String key, String value) {
        if (key == null || key.isBlank()) {
            return true;
        }
        String actual = node.metadata().get(key);
        return value == null ? actual != null : value.equals(actual);
    }

    private TreeNodeDto buildTree(List<ContextNode> flat, ContextNode root) {
        Map<CtxfsUri, List<ContextNode>> byParent = new HashMap<>();
        for (ContextNode node : flat) {
            if (node.parent() != null && !node.uri().equals(root.uri())) {
                byParent.computeIfAbsent(node.parent(), key -> new ArrayList<>()).add(node);
            }
        }
        return buildNode(byParent, root);
    }

    private TreeNodeDto buildNode(Map<CtxfsUri, List<ContextNode>> byParent, ContextNode node) {
        List<TreeNodeDto> children =
                byParent.getOrDefault(node.uri(), List.of()).stream()
                        .map(child -> buildNode(byParent, child))
                        .toList();
        return TreeNodeDto.from(node, children);
    }

    private Optional<CachedLevel> getFromCache(String key) {
        try {
            String json = cacheStore.get(key).orElse(null);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, CachedLevel.class));
        } catch (RuntimeException | JsonProcessingException e) {
            log.debug("Cache read degraded: key={}, error={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private void putToCache(String key, CachedLevel value) {
        try {
            cacheStore.put(key, objectMapper.writeValueAsString(value), cacheTtl);
        } catch (RuntimeException | JsonProcessingException e) {
            log.debug("Cache write skipped: key={}, error={}", key, e.getMessage());
        }
    }

    private void evictQuietly(String prefix) {
        try {
            cacheStore.evictByPrefix(prefix);
        } catch (RuntimeException e) {
            log.debug("Cache eviction skipped: prefix={}, error={}", prefix, e.getMessage());
        }
    }
}
