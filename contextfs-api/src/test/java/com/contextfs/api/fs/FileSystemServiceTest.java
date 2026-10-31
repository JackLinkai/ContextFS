package com.contextfs.api.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.contextfs.api.dto.NodeDto;
import com.contextfs.api.dto.ReadResultDto;
import com.contextfs.api.dto.TreeNodeDto;
import com.contextfs.api.dto.WriteResultDto;
import com.contextfs.core.exception.NodeNotFoundException;
import com.contextfs.core.exception.ScopeViolationException;
import com.contextfs.core.inmem.InMemoryCacheStore;
import com.contextfs.core.inmem.InMemoryNodeRepository;
import com.contextfs.core.model.Level;
import com.contextfs.core.model.NodeType;
import com.contextfs.core.port.CacheStore;
import com.contextfs.core.uri.CtxfsUri;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FileSystemService")
class FileSystemServiceTest {

    private InMemoryNodeRepository repository;
    private InMemoryCacheStore cache;
    private FileSystemService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryNodeRepository();
        cache = new InMemoryCacheStore();
        service =
                new FileSystemService(
                        repository, cache, new ObjectMapper(), Duration.ofMinutes(10));
    }

    @Nested
    @DisplayName("write 写入节点")
    class Write {

        @Test
        @DisplayName("写入新节点自动创建父目录，内容保存为 L2")
        void writeCreatesMissingParents() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://user/10001/memories/preferences.md");

            WriteResultDto result =
                    service.write(
                            uri,
                            "用户偏好简洁回答，代码示例优先使用 Java。",
                            NodeType.MEMORY,
                            Map.of("source", "session"),
                            null);

            assertThat(result.uri()).isEqualTo(uri.toString());
            assertThat(result.type()).isEqualTo(NodeType.MEMORY);
            assertThat(repository.exists(uri)).isTrue();
            assertThat(repository.exists(CtxfsUri.parse("ctxfs://user/10001"))).isTrue();
            assertThat(repository.exists(CtxfsUri.parse("ctxfs://user/10001/memories"))).isTrue();
            assertThat(repository.findLevel(uri, Level.L2))
                    .hasValueSatisfying(
                            level -> {
                                assertThat(level.content()).isEqualTo("用户偏好简洁回答，代码示例优先使用 Java。");
                                assertThat(level.tokenCount()).isPositive();
                            });
        }

        @Test
        @DisplayName("未指定类型时默认 DOCUMENT，重复写入覆盖内容且保留 createdAt")
        void writeOverwriteKeepsCreatedAt() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://global/notes/a.md");
            service.write(uri, "第一版", null, null, null);
            var before = repository.findByUri(uri).orElseThrow();

            service.write(uri, "第二版", null, null, null);

            var after = repository.findByUri(uri).orElseThrow();
            assertThat(after.type()).isEqualTo(NodeType.DOCUMENT);
            assertThat(after.createdAt()).isEqualTo(before.createdAt());
            assertThat(repository.findLevel(uri, Level.L2).orElseThrow().content())
                    .isEqualTo("第二版");
        }

        @Test
        @DisplayName("提供 metadata 时整体覆盖，未提供时保留原值")
        void writeMetadataReplaceOrKeep() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://global/notes/a.md");
            service.write(uri, "内容", null, Map.of("k1", "v1", "k2", "v2"), null);

            service.write(uri, "内容2", null, Map.of("k1", "v1-new"), null);
            assertThat(repository.findByUri(uri).orElseThrow().metadata())
                    .containsEntry("k1", "v1-new")
                    .doesNotContainKey("k2");

            service.write(uri, "内容3", null, null, null);
            assertThat(repository.findByUri(uri).orElseThrow().metadata())
                    .containsEntry("k1", "v1-new");
        }

        @Test
        @DisplayName("拒绝向 scope 根节点写入内容")
        void rejectRootWrite() {
            assertThatThrownBy(
                            () ->
                                    service.write(
                                            CtxfsUri.parse("ctxfs://global"),
                                            "内容",
                                            null,
                                            null,
                                            null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("read 读取节点")
    class Read {

        @Test
        @DisplayName("L2 返回写入的完整原文")
        void readL2Content() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://global/docs/a.md");
            service.write(uri, "完整原文", null, null, null);

            ReadResultDto result = service.read(uri, Level.L2, null);

            assertThat(result.content()).isEqualTo("完整原文");
            assertThat(result.generated()).isTrue();
            assertThat(result.tokenCount()).isPositive();
        }

        @Test
        @DisplayName("L0/L1 摘要未生成时返回占位文本")
        void readPlaceholderForMissingLevels() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://global/docs/a.md");
            service.write(uri, "完整原文", null, null, null);

            ReadResultDto l0 = service.read(uri, Level.L0, null);
            assertThat(l0.generated()).isFalse();
            assertThat(l0.content()).isEqualTo(FileSystemService.placeholder(Level.L0));

            ReadResultDto l1 = service.read(uri, Level.L1, null);
            assertThat(l1.generated()).isFalse();
        }

        @Test
        @DisplayName("节点不存在时抛 NodeNotFoundException")
        void readMissingNode() {
            assertThatThrownBy(
                            () ->
                                    service.read(
                                            CtxfsUri.parse("ctxfs://global/none/a.md"),
                                            Level.L2,
                                            null))
                    .isInstanceOf(NodeNotFoundException.class);
        }

        @Test
        @DisplayName("首次读取回填缓存，二次读取命中缓存")
        void readFillsCache() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://global/docs/a.md");
            service.write(uri, "完整原文", null, null, null);
            String cacheKey = FileSystemService.CACHE_READ_PREFIX + uri + ":" + Level.L2;

            service.read(uri, Level.L2, null);
            assertThat(cache.get(cacheKey)).isPresent();

            // 用不同的缓存值证明二次读取来自缓存而非仓储
            cache.put(cacheKey, "{\"content\":\"缓存内容\",\"tokenCount\":4}", null);
            ReadResultDto result = service.read(uri, Level.L2, null);
            assertThat(result.content()).isEqualTo("缓存内容");
        }

        @Test
        @DisplayName("write 后旧缓存被失效，读取到新内容")
        void writeInvalidatesCache() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://global/docs/a.md");
            service.write(uri, "第一版", null, null, null);
            service.read(uri, Level.L2, null);

            service.write(uri, "第二版", null, null, null);
            ReadResultDto result = service.read(uri, Level.L2, null);
            assertThat(result.content()).isEqualTo("第二版");
        }

        @Test
        @DisplayName("缓存故障时降级到仓储，不影响读写主流程")
        void degradeWhenCacheFails() {
            FileSystemService serviceWithBadCache =
                    new FileSystemService(
                            repository,
                            new FailingCacheStore(),
                            new ObjectMapper(),
                            Duration.ofMinutes(10));
            CtxfsUri uri = CtxfsUri.parse("ctxfs://global/docs/a.md");

            serviceWithBadCache.write(uri, "内容", null, null, null);
            ReadResultDto result = serviceWithBadCache.read(uri, Level.L2, null);
            assertThat(result.content()).isEqualTo("内容");
            assertThat(result.generated()).isTrue();
        }
    }

    @Nested
    @DisplayName("ls 列出目录")
    class Ls {

        @Test
        @DisplayName("列出直接子节点，不包含孙子节点")
        void lsDirectChildrenOnly() {
            CtxfsUri memories = CtxfsUri.parse("ctxfs://user/10001/memories");
            service.write(
                    CtxfsUri.parse("ctxfs://user/10001/memories/preferences.md"),
                    "内容",
                    null,
                    null,
                    null);
            service.write(
                    CtxfsUri.parse("ctxfs://user/10001/memories/facts.md"), "内容", null, null, null);
            service.write(CtxfsUri.parse("ctxfs://user/10001/notes/a.md"), "内容", null, null, null);

            var children = service.ls(memories, null);

            assertThat(children)
                    .extracting(NodeDto::uri)
                    .containsExactly(
                            CtxfsUri.parse("ctxfs://user/10001/memories/facts.md").toString(),
                            CtxfsUri.parse("ctxfs://user/10001/memories/preferences.md")
                                    .toString());
        }

        @Test
        @DisplayName("scope 根节点允许隐式存在，返回空列表")
        void lsImplicitScopeRoot() {
            assertThat(service.ls(CtxfsUri.parse("ctxfs://global"), null)).isEmpty();
        }

        @Test
        @DisplayName("目录不存在时抛 NodeNotFoundException")
        void lsMissingDirectory() {
            assertThatThrownBy(() -> service.ls(CtxfsUri.parse("ctxfs://global/none"), null))
                    .isInstanceOf(NodeNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("tree 目录树")
    class Tree {

        @Test
        @DisplayName("返回嵌套子树，children 挂在对应父节点下")
        void nestedTree() {
            service.write(
                    CtxfsUri.parse("ctxfs://user/10001/memories/preferences.md"),
                    "内容",
                    null,
                    null,
                    null);
            service.write(CtxfsUri.parse("ctxfs://user/10001/notes/a.md"), "内容", null, null, null);

            TreeNodeDto tree = service.tree(CtxfsUri.parse("ctxfs://user/10001"), 8, null);

            assertThat(tree.uri()).isEqualTo("ctxfs://user/10001");
            assertThat(tree.children()).hasSize(2);
            TreeNodeDto notes =
                    tree.children().stream()
                            .filter(node -> node.name().equals("notes"))
                            .findFirst()
                            .orElseThrow();
            assertThat(notes.children()).extracting(TreeNodeDto::name).containsExactly("a.md");
        }

        @Test
        @DisplayName("maxDepth=0 仅返回根节点")
        void depthZeroOnlyRoot() {
            service.write(CtxfsUri.parse("ctxfs://global/docs/a.md"), "内容", null, null, null);

            TreeNodeDto tree = service.tree(CtxfsUri.parse("ctxfs://global/docs"), 0, null);

            assertThat(tree.name()).isEqualTo("docs");
            assertThat(tree.children()).isEmpty();
        }

        @Test
        @DisplayName("scope 根节点尚未创建时返回虚拟根")
        void implicitScopeRoot() {
            TreeNodeDto tree = service.tree(CtxfsUri.parse("ctxfs://global"), 8, null);
            assertThat(tree.uri()).isEqualTo("ctxfs://global");
            assertThat(tree.children()).isEmpty();
        }

        @Test
        @DisplayName("非根节点不存在时抛 NodeNotFoundException")
        void missingNode() {
            assertThatThrownBy(() -> service.tree(CtxfsUri.parse("ctxfs://global/none"), 8, null))
                    .isInstanceOf(NodeNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("find 查找节点")
    class Find {

        @BeforeEach
        void seed() {
            service.write(
                    CtxfsUri.parse("ctxfs://global/skills/search/web_search.md"),
                    "内容",
                    NodeType.SKILL,
                    Map.of("category", "search"),
                    null);
            service.write(
                    CtxfsUri.parse("ctxfs://global/skills/search/web_search_v2.md"),
                    "内容",
                    NodeType.SKILL,
                    Map.of("category", "search"),
                    null);
            service.write(
                    CtxfsUri.parse("ctxfs://global/docs/java.md"),
                    "内容",
                    NodeType.DOCUMENT,
                    Map.of("category", "doc"),
                    null);
        }

        @Test
        @DisplayName("按 URI 关键词过滤（大小写不敏感）")
        void findByKeyword() {
            var result =
                    service.find(
                            CtxfsUri.parse("ctxfs://global"),
                            "WEB_SEARCH.MD",
                            null,
                            null,
                            null,
                            null);
            assertThat(result).extracting(NodeDto::name).containsExactly("web_search.md");
        }

        @Test
        @DisplayName("按节点类型过滤")
        void findByType() {
            var result =
                    service.find(
                            CtxfsUri.parse("ctxfs://global"),
                            null,
                            NodeType.DOCUMENT,
                            null,
                            null,
                            null);
            assertThat(result).extracting(NodeDto::name).containsExactly("java.md");
        }

        @Test
        @DisplayName("按元数据键值过滤")
        void findByMetadata() {
            var result =
                    service.find(
                            CtxfsUri.parse("ctxfs://global"),
                            null,
                            null,
                            "category",
                            "search",
                            null);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("无条件时返回子树全部节点（含根与中间目录）")
        void findAll() {
            var result =
                    service.find(CtxfsUri.parse("ctxfs://global"), null, null, null, null, null);
            // global 根 + skills + search + 2 个 skill + docs + java.md = 7
            assertThat(result).hasSize(7);
        }

        @Test
        @DisplayName("无匹配时返回空列表")
        void noMatch() {
            var result =
                    service.find(
                            CtxfsUri.parse("ctxfs://global"), "python", null, null, null, null);
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("scope 隔离校验")
    class Scope {

        @Test
        @DisplayName("allowedScope 与 URI scope 不一致时抛 ScopeViolationException")
        void scopeMismatchRejected() {
            assertThatThrownBy(
                            () ->
                                    service.read(
                                            CtxfsUri.parse("ctxfs://user/10001/notes/a.md"),
                                            Level.L2,
                                            "user/99999"))
                    .isInstanceOf(ScopeViolationException.class);
        }

        @Test
        @DisplayName("allowedScope 一致时正常访问")
        void scopeMatchAllowed() {
            service.write(
                    CtxfsUri.parse("ctxfs://user/10001/notes/a.md"),
                    "内容",
                    null,
                    null,
                    "user/10001");
            var result =
                    service.read(
                            CtxfsUri.parse("ctxfs://user/10001/notes/a.md"),
                            Level.L2,
                            "user/10001");
            assertThat(result.content()).isEqualTo("内容");
        }

        @Test
        @DisplayName("allowedScope 为 null 时不限制访问")
        void nullScopeUnrestricted() {
            var result = service.ls(CtxfsUri.parse("ctxfs://global"), null);
            assertThat(result).isNotNull();
        }
    }

    @Test
    @DisplayName("token 估算：CJK 字符按 1 token，其他按每 4 字符 1 token")
    void estimateTokens() {
        assertThat(FileSystemService.estimateTokens("")).isZero();
        assertThat(FileSystemService.estimateTokens("用户偏好")).isEqualTo(4);
        assertThat(FileSystemService.estimateTokens("abcdefgh")).isEqualTo(2);
    }

    /** 始终抛异常的 CacheStore，用于验证缓存故障降级。 */
    static class FailingCacheStore implements CacheStore {
        @Override
        public java.util.Optional<String> get(String key) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public void put(String key, String value, Duration ttl) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public void evict(String key) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public void evictByPrefix(String prefix) {
            throw new IllegalStateException("redis down");
        }
    }
}
