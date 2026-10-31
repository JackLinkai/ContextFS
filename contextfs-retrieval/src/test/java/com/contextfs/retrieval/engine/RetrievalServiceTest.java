package com.contextfs.retrieval.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.contextfs.core.inmem.InMemoryNodeRepository;
import com.contextfs.core.inmem.InMemoryVectorStore;
import com.contextfs.core.model.ContextNode;
import com.contextfs.core.model.Level;
import com.contextfs.core.model.NodeLevel;
import com.contextfs.core.model.NodeType;
import com.contextfs.core.port.VectorRecord;
import com.contextfs.core.uri.CtxfsUri;
import com.contextfs.core.util.Tokens;
import com.contextfs.retrieval.support.HashingEmbeddingClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** {@link RetrievalService} 单元测试：InMemory 装配 + 哈希嵌入，不依赖外部服务。 */
@DisplayName("RetrievalService 检索引擎")
class RetrievalServiceTest {

    private static final RetrievalConfig CONFIG = new RetrievalConfig(5, Level.L1, 0.3, 4096, 3);

    private InMemoryNodeRepository repository;
    private InMemoryVectorStore vectorStore;
    private HashingEmbeddingClient embeddingClient;
    private RetrievalService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryNodeRepository();
        vectorStore = new InMemoryVectorStore();
        embeddingClient = new HashingEmbeddingClient();
        service = new RetrievalService(repository, vectorStore, embeddingClient, CONFIG);
        writeCorpus();
    }

    /** 写入节点并保存 L0 摘要 + L0 向量。 */
    private void node(String scope, String path, NodeType type, String l0) {
        CtxfsUri uri = CtxfsUri.of(scope, path);
        repository.save(
                new ContextNode(
                        uri,
                        type,
                        uri.parent().orElse(null),
                        java.util.Map.of(),
                        Instant.now(),
                        Instant.now()));
        repository.saveLevel(uri, new NodeLevel(Level.L0, l0, Tokens.estimate(l0)));
        vectorStore.upsert(new VectorRecord(uri, Level.L0, l0, embeddingClient.embed(l0)));
    }

    /** 写入文档节点：L0 摘要 + 向量，另存 L1 / L2 完整层级。 */
    private void doc(String scope, String path, String l0, String l1, String l2) {
        node(scope, path, NodeType.DOCUMENT, l0);
        CtxfsUri uri = CtxfsUri.of(scope, path);
        repository.saveLevel(uri, new NodeLevel(Level.L1, l1, Tokens.estimate(l1)));
        repository.saveLevel(uri, new NodeLevel(Level.L2, l2, Tokens.estimate(l2)));
    }

    private void writeCorpus() {
        node("global", "skills", NodeType.DIRECTORY, "技能目录：网络搜索、文件编辑与代码审查等技能说明。");
        doc(
                "global",
                "skills/web_search.md",
                "网络搜索技能：调用搜索引擎检索网页，返回结果列表与页面摘要。",
                "web_search 概览：入参 query 与 topK，出参为网页标题、链接与摘要。",
                "web_search 完整文档：调用搜索引擎 API 检索网页，支持翻页、时间过滤与站点限定。");
        doc(
                "global",
                "skills/file_edit.md",
                "文件编辑技能：读取本地文件内容，按行修改并保存写回。",
                "file_edit 概览：支持按行号替换、追加与整文件重写。",
                "file_edit 完整文档：读取文件、定位行、替换内容并写回磁盘的完整流程。");
        node("global", "docs", NodeType.DIRECTORY, "文档目录：REST API 使用指南与错误码说明。");
        doc(
                "global",
                "docs/api_guide.md",
                "API 使用指南：write 写入、read 读取、ls 列目录与 find 查找接口。",
                "API 概览：各接口的请求参数、响应结构与鉴权头说明。",
                "API 完整文档：write/read/ls/tree/find 的请求响应示例与错误处理。");
        node("global", "memories", NodeType.DIRECTORY, "记忆目录：保存用户偏好与项目背景。");
        doc(
                "global",
                "memories/preferences.md",
                "用户偏好记忆：偏好中文回复、简洁风格与暗色主题界面。",
                "偏好概览：语言、风格与主题三类偏好。",
                "偏好完整记录：中文回复、简洁风格、暗色主题、Java 技术栈。");
    }

    @Nested
    @DisplayName("SEARCH 模式（语义检索 + 目录下钻）")
    class Search {

        @Test
        @DisplayName("命中目录后递归下钻出子节点，结果按分数降序")
        void searchDrillsIntoMatchedDirectory() {
            RetrievalResult result =
                    service.retrieve("global", "技能目录 网络搜索", RetrievalMode.SEARCH, null, "trace-1");

            List<String> uris = result.items().stream().map(RetrievalItem::uri).toList();
            assertThat(uris).contains("ctxfs://global/skills/web_search.md");
            assertThat(uris).contains("ctxfs://global/skills");
            assertThat(result.items())
                    .isSortedAccordingTo(
                            java.util.Comparator.comparingDouble(RetrievalItem::score).reversed());
        }

        @Test
        @DisplayName("分数阈值剪枝：阈值极高时不返回任何结果")
        void scoreThresholdPrunes() {
            RetrievalConfig cfg = new RetrievalConfig(5, Level.L1, 0.999, 4096, 3);
            RetrievalResult result =
                    service.retrieve("global", "网络搜索 网页摘要", RetrievalMode.SEARCH, cfg, null);

            assertThat(result.items()).isEmpty();
        }

        @Test
        @DisplayName("maxDepth 限制下钻深度：深层节点不进入结果")
        void maxDepthLimitsDrillDown() {
            node("global", "deep", NodeType.DIRECTORY, "深层目录入口：存放嵌套配置说明。");
            node("global", "deep/level1", NodeType.DIRECTORY, "level1 目录：嵌套子目录。");
            node("global", "deep/level1/level2", NodeType.DIRECTORY, "level2 目录：更深层。");
            node("global", "deep/level1/level2/leaf.md", NodeType.DOCUMENT, "叶子文档：最深层的配置说明。");

            RetrievalConfig cfg = new RetrievalConfig(1, Level.L1, 0.1, 4096, 1);
            RetrievalResult result =
                    service.retrieve("global", "深层目录入口 嵌套配置", RetrievalMode.SEARCH, cfg, null);

            List<String> uris = result.items().stream().map(RetrievalItem::uri).toList();
            assertThat(uris).contains("ctxfs://global/deep/level1");
            assertThat(uris).noneMatch(uri -> uri.contains("level2") || uri.contains("leaf.md"));
        }

        @Test
        @DisplayName("token 预算截断：除首条外累计不超过预算")
        void tokenBudgetTruncates() {
            RetrievalConfig cfg = new RetrievalConfig(5, Level.L1, 0.0, 100, 3);
            RetrievalResult result =
                    service.retrieve(
                            "global", "技能 搜索 文件 代码 API 偏好", RetrievalMode.SEARCH, cfg, null);

            assertThat(result.items()).hasSizeLessThan(8);
            int total = result.items().stream().mapToInt(i -> Tokens.estimate(i.content())).sum();
            int first = Tokens.estimate(result.items().get(0).content());
            assertThat(total - first).isLessThanOrEqualTo(100);
        }

        @Test
        @DisplayName("maxLevel=L2 时文档返回 L2 原文，目录降级到 L0")
        void maxLevelControlsLoadedContent() {
            RetrievalConfig cfg = new RetrievalConfig(5, Level.L2, 0.0, 0, 3);
            RetrievalResult result =
                    service.retrieve("global", "网络搜索 网页摘要", RetrievalMode.SEARCH, cfg, null);

            assertThat(result.items()).isNotEmpty();
            result.items()
                    .forEach(
                            item -> {
                                if (item.uri().endsWith("web_search.md")) {
                                    assertThat(item.level()).isEqualTo(Level.L2);
                                    assertThat(item.content()).startsWith("web_search 完整文档");
                                }
                            });
            result.items().stream()
                    .filter(item -> item.uri().equals("ctxfs://global/skills"))
                    .findFirst()
                    .ifPresent(item -> assertThat(item.level()).isEqualTo(Level.L0));
        }

        @Test
        @DisplayName("向量依赖缺失时降级词法检索")
        void lexicalFallbackWithoutVectorDependencies() {
            RetrievalService degraded = new RetrievalService(repository, null, null, CONFIG);
            RetrievalResult result =
                    degraded.retrieve("global", "api_guide", RetrievalMode.SEARCH, null, null);

            List<String> uris = result.items().stream().map(RetrievalItem::uri).toList();
            assertThat(uris).contains("ctxfs://global/docs/api_guide.md");
            assertThat(result.items().get(0).score()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("scope 隔离：不返回其他 scope 的内容")
        void searchIsolatesScope() {
            doc("user/10001", "notes/private.md", "私人笔记：网络搜索的密钥与账号信息。", "私人概览。", "私人完整内容。");

            RetrievalResult result =
                    service.retrieve("global", "网络搜索 密钥 账号", RetrievalMode.SEARCH, null, null);

            List<String> uris = result.items().stream().map(RetrievalItem::uri).toList();
            assertThat(uris).noneMatch(uri -> uri.startsWith("ctxfs://user/10001"));
        }
    }

    @Nested
    @DisplayName("FIND 模式（URI / 内容精确匹配）")
    class Find {

        @Test
        @DisplayName("URI 关键词命中得 1.0")
        void findMatchesUriKeyword() {
            RetrievalResult result =
                    service.retrieve("global", "web_search.md", RetrievalMode.FIND, null, null);

            assertThat(result.items()).hasSizeGreaterThanOrEqualTo(1);
            assertThat(result.items().get(0).uri())
                    .isEqualTo("ctxfs://global/skills/web_search.md");
            assertThat(result.items().get(0).score()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("L0 内容关键词命中得 0.5")
        void findMatchesL0Content() {
            RetrievalResult result =
                    service.retrieve("global", "暗色主题", RetrievalMode.FIND, null, null);

            assertThat(result.items()).isNotEmpty();
            assertThat(result.items().get(0).uri())
                    .isEqualTo("ctxfs://global/memories/preferences.md");
            assertThat(result.items().get(0).score()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("topK 截断结果条数")
        void findTruncatesToTopK() {
            RetrievalConfig cfg = new RetrievalConfig(1, Level.L1, 0.3, 4096, 3);
            RetrievalResult result =
                    service.retrieve("global", "技能", RetrievalMode.FIND, cfg, null);

            assertThat(result.items()).hasSizeLessThanOrEqualTo(1);
        }

        @Test
        @DisplayName("scope 根节点隐式存在时也能 find")
        void findOnImplicitRoot() {
            repository = new InMemoryNodeRepository();
            vectorStore = new InMemoryVectorStore();
            service = new RetrievalService(repository, vectorStore, embeddingClient, CONFIG);
            doc("global", "a.md", "苹果说明文档。", "a 概览。", "a 完整。");

            RetrievalResult result =
                    service.retrieve("global", "a.md", RetrievalMode.FIND, null, null);

            assertThat(result.items()).hasSize(1);
            assertThat(result.items().get(0).uri()).isEqualTo("ctxfs://global/a.md");
        }
    }

    @Nested
    @DisplayName("结果元数据")
    class Metadata {

        @Test
        @DisplayName("traceId 透传，tokenUsage 与 latencyMs 统计正确")
        void resultMetadata() {
            RetrievalResult result =
                    service.retrieve("global", "网络搜索", RetrievalMode.SEARCH, null, "trace-42");

            assertThat(result.traceId()).isEqualTo("trace-42");
            assertThat(result.mode()).isEqualTo(RetrievalMode.SEARCH);
            assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
            int expected =
                    result.items().stream().mapToInt(item -> Tokens.estimate(item.content())).sum();
            assertThat(result.tokenUsage().promptTokens()).isEqualTo(expected);
            assertThat(result.tokenUsage().completionTokens()).isZero();
        }

        @Test
        @DisplayName("traceId 缺失时自动生成")
        void traceIdGeneratedWhenAbsent() {
            RetrievalResult result =
                    service.retrieve("global", "网络搜索", RetrievalMode.SEARCH, null, " ");

            assertThat(result.traceId()).isNotBlank();
        }
    }
}
