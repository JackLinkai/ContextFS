package com.contextfs.core.inmem;

import com.contextfs.core.model.ContextNode;
import com.contextfs.core.model.Level;
import com.contextfs.core.model.NodeLevel;
import com.contextfs.core.port.NodeRepository;
import com.contextfs.core.uri.CtxfsUri;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的 {@link NodeRepository} 实现：供单元测试、本地开发与后续集成测试复用，无外部依赖。
 */
public class InMemoryNodeRepository implements NodeRepository {

    private final Map<CtxfsUri, ContextNode> nodes = new ConcurrentHashMap<>();
    private final Map<CtxfsUri, Map<Level, NodeLevel>> levels = new ConcurrentHashMap<>();

    @Override
    public void save(ContextNode node) {
        Objects.requireNonNull(node, "node");
        nodes.put(node.uri(), node);
    }

    @Override
    public Optional<ContextNode> findByUri(CtxfsUri uri) {
        return Optional.ofNullable(nodes.get(uri));
    }

    @Override
    public boolean exists(CtxfsUri uri) {
        return nodes.containsKey(uri);
    }

    @Override
    public List<ContextNode> listChildren(CtxfsUri parent) {
        return nodes.values().stream()
                .filter(node -> Objects.equals(node.parent(), parent))
                .sorted(Comparator.comparing(node -> node.uri().name()))
                .toList();
    }

    @Override
    public List<ContextNode> listSubtree(CtxfsUri root, int maxDepth) {
        List<ContextNode> result = new ArrayList<>();
        ContextNode rootNode = nodes.get(root);
        if (rootNode == null) {
            return result;
        }
        result.add(rootNode);
        if (maxDepth <= 0) {
            return result;
        }
        Deque<Map.Entry<CtxfsUri, Integer>> queue = new ArrayDeque<>();
        queue.add(Map.entry(root, 0));
        while (!queue.isEmpty()) {
            var current = queue.poll();
            if (current.getValue() >= maxDepth) {
                continue;
            }
            for (ContextNode child : listChildren(current.getKey())) {
                result.add(child);
                queue.add(Map.entry(child.uri(), current.getValue() + 1));
            }
        }
        result.sort(Comparator.comparing(node -> node.uri().toString()));
        return result;
    }

    @Override
    public void delete(CtxfsUri uri) {
        nodes.remove(uri);
        levels.remove(uri);
    }

    @Override
    public void saveLevel(CtxfsUri uri, NodeLevel level) {
        Objects.requireNonNull(level, "level");
        levels.computeIfAbsent(uri, key -> new HashMap<>()).put(level.level(), level);
    }

    @Override
    public Optional<NodeLevel> findLevel(CtxfsUri uri, Level level) {
        return Optional.ofNullable(levels.get(uri)).map(map -> map.get(level));
    }
}
