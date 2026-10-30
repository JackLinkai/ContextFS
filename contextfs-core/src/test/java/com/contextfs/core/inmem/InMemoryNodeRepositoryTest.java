package com.contextfs.core.inmem;

import static org.assertj.core.api.Assertions.assertThat;

import com.contextfs.core.model.ContextNode;
import com.contextfs.core.model.Level;
import com.contextfs.core.model.NodeLevel;
import com.contextfs.core.model.NodeType;
import com.contextfs.core.uri.CtxfsUri;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryNodeRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");

    private InMemoryNodeRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryNodeRepository();
        seed(
                "ctxfs://user/10001",
                NodeType.DIRECTORY,
                "ctxfs://user/10001/memories",
                NodeType.DIRECTORY,
                "ctxfs://user/10001/memories/preferences.md",
                NodeType.MEMORY,
                "ctxfs://user/10001/memories/facts.md",
                NodeType.MEMORY,
                "ctxfs://user/10001/resources",
                NodeType.DIRECTORY,
                "ctxfs://user/10001/resources/java",
                NodeType.DIRECTORY,
                "ctxfs://user/10001/resources/java/concurrency.md",
                NodeType.DOCUMENT);
    }

    private void seed(Object... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            CtxfsUri uri = CtxfsUri.parse((String) pairs[i]);
            repository.save(ContextNode.create(uri, (NodeType) pairs[i + 1], NOW));
        }
    }

    @Test
    @DisplayName("save 后可按 URI 查询且 exists 为真")
    void saveAndFindByUri() {
        CtxfsUri uri = CtxfsUri.parse("ctxfs://user/10001/memories/preferences.md");
        var node = repository.findByUri(uri);
        assertThat(node).isPresent();
        assertThat(node.get().type()).isEqualTo(NodeType.MEMORY);
        assertThat(node.get().parent()).isEqualTo(CtxfsUri.parse("ctxfs://user/10001/memories"));
        assertThat(repository.exists(uri)).isTrue();
    }

    @Test
    @DisplayName("查询不存在的节点返回空且 exists 为假")
    void findMissing() {
        CtxfsUri uri = CtxfsUri.parse("ctxfs://user/10001/missing.md");
        assertThat(repository.findByUri(uri)).isEmpty();
        assertThat(repository.exists(uri)).isFalse();
    }

    @Test
    @DisplayName("save 覆盖同名节点并更新元数据")
    void saveOverwrites() {
        CtxfsUri uri = CtxfsUri.parse("ctxfs://user/10001/memories/preferences.md");
        ContextNode updated =
                repository
                        .findByUri(uri)
                        .orElseThrow()
                        .withMetadata(Map.of("source", "pipeline"), NOW.plusSeconds(1));
        repository.save(updated);

        var reloaded = repository.findByUri(uri).orElseThrow();
        assertThat(reloaded.metadata()).containsEntry("source", "pipeline");
        assertThat(reloaded.updatedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(reloaded.createdAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("listChildren 只返回直接子节点且按名称排序")
    void listChildrenReturnsDirectChildrenSorted() {
        List<ContextNode> children =
                repository.listChildren(CtxfsUri.parse("ctxfs://user/10001"));
        assertThat(children)
                .extracting(node -> node.uri().name())
                .containsExactly("memories", "resources");

        List<ContextNode> memories =
                repository.listChildren(CtxfsUri.parse("ctxfs://user/10001/memories"));
        assertThat(memories)
                .extracting(node -> node.uri().name())
                .containsExactly("facts.md", "preferences.md");
    }

    @Test
    @DisplayName("listChildren 对不存在或叶子目录返回空列表")
    void listChildrenOfMissingParentIsEmpty() {
        assertThat(repository.listChildren(CtxfsUri.parse("ctxfs://user/99999"))).isEmpty();
        assertThat(
                        repository.listChildren(
                                CtxfsUri.parse(
                                        "ctxfs://user/10001/memories/preferences.md")))
                .isEmpty();
    }

    @Test
    @DisplayName("listSubtree maxDepth=0 仅返回根节点")
    void listSubtreeDepthZero() {
        List<ContextNode> subtree =
                repository.listSubtree(CtxfsUri.parse("ctxfs://user/10001"), 0);
        assertThat(subtree)
                .hasSize(1)
                .first()
                .extracting(node -> node.uri())
                .isEqualTo(CtxfsUri.parse("ctxfs://user/10001"));
    }

    @Test
    @DisplayName("listSubtree 按深度展开，不越界")
    void listSubtreeRespectsDepth() {
        List<ContextNode> depth1 =
                repository.listSubtree(CtxfsUri.parse("ctxfs://user/10001"), 1);
        assertThat(depth1).hasSize(3);

        List<ContextNode> depth2 =
                repository.listSubtree(CtxfsUri.parse("ctxfs://user/10001"), 2);
        assertThat(depth2).hasSize(6);

        List<ContextNode> depth9 =
                repository.listSubtree(CtxfsUri.parse("ctxfs://user/10001"), 9);
        assertThat(depth9).hasSize(7);
    }

    @Test
    @DisplayName("listSubtree 根不存在时返回空")
    void listSubtreeMissingRoot() {
        assertThat(repository.listSubtree(CtxfsUri.parse("ctxfs://user/404"), 3)).isEmpty();
    }

    @Test
    @DisplayName("saveLevel / findLevel 按 (uri, level) 存取")
    void saveAndFindLevel() {
        CtxfsUri uri = CtxfsUri.parse("ctxfs://user/10001/memories/preferences.md");
        NodeLevel l0 = new NodeLevel(Level.L0, "用户偏好摘要", 10);
        NodeLevel l2 = new NodeLevel(Level.L2, "用户偏好简洁回答，代码示例优先使用 Java。", 30);
        repository.saveLevel(uri, l0);
        repository.saveLevel(uri, l2);

        assertThat(repository.findLevel(uri, Level.L0)).hasValue(l0);
        assertThat(repository.findLevel(uri, Level.L2)).hasValue(l2);
        assertThat(repository.findLevel(uri, Level.L1)).isEmpty();
        assertThat(repository.findLevel(CtxfsUri.parse("ctxfs://user/10001"), Level.L0))
                .isEmpty();
    }

    @Test
    @DisplayName("saveLevel 覆盖同层级旧内容")
    void saveLevelOverwrites() {
        CtxfsUri uri = CtxfsUri.parse("ctxfs://user/10001/memories/preferences.md");
        repository.saveLevel(uri, new NodeLevel(Level.L0, "旧摘要", 5));
        repository.saveLevel(uri, new NodeLevel(Level.L0, "新摘要", 6));
        assertThat(repository.findLevel(uri, Level.L0)).hasValue(new NodeLevel(Level.L0, "新摘要", 6));
    }

    @Test
    @DisplayName("delete 删除节点及其层级内容，但不级联删除子节点")
    void deleteWithoutCascade() {
        CtxfsUri parent = CtxfsUri.parse("ctxfs://user/10001/memories");
        CtxfsUri child = CtxfsUri.parse("ctxfs://user/10001/memories/preferences.md");
        repository.saveLevel(child, new NodeLevel(Level.L0, "摘要", 4));

        repository.delete(parent);

        assertThat(repository.exists(parent)).isFalse();
        assertThat(repository.exists(child)).isTrue();
        assertThat(repository.findLevel(child, Level.L0))
                .hasValue(new NodeLevel(Level.L0, "摘要", 4));
        assertThat(repository.findLevel(parent, Level.L0)).isEmpty();
    }

    @Test
    @DisplayName("delete 不存在的节点不抛异常")
    void deleteMissingIsNoop() {
        repository.delete(CtxfsUri.parse("ctxfs://user/10001/ghost"));
        assertThat(repository.exists(CtxfsUri.parse("ctxfs://user/10001/ghost"))).isFalse();
    }
}
