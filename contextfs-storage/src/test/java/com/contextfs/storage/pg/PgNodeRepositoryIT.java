package com.contextfs.storage.pg;

import static org.assertj.core.api.Assertions.assertThat;

import com.contextfs.core.model.ContextNode;
import com.contextfs.core.model.Level;
import com.contextfs.core.model.NodeLevel;
import com.contextfs.core.model.NodeType;
import com.contextfs.core.uri.CtxfsUri;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PgNodeRepository 集成测试：Testcontainers PostgreSQL + Flyway 真实迁移。
 *
 * <p>仅在 {@code integration} profile 下由 failsafe 执行：{@code mvn verify -Pintegration}（需 Docker）。本机无
 * Docker 时跳过。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("PgNodeRepository 集成测试（PostgreSQL）")
class PgNodeRepositoryIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    private PgNodeRepository repository;
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void setUp() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new PgNodeRepository(jdbcTemplate, new ObjectMapper());
    }

    private ContextNode node(String scope, String path, NodeType type) {
        return ContextNode.create(CtxfsUri.of(scope, path), type, Instant.now());
    }

    @Nested
    @DisplayName("节点 CRUD")
    class NodeCrud {

        @Test
        @DisplayName("保存后按 URI 查询，元数据与父子关系一致")
        void saveAndFind() {
            ContextNode input =
                    node("user/10001", "docs/api.md", NodeType.DOCUMENT)
                            .withMetadata(
                                    Map.of("title", "API Guide", "source", "obsidian"),
                                    Instant.now());
            repository.save(input);

            Optional<ContextNode> found = repository.findByUri(input.uri());
            assertThat(found).isPresent();
            ContextNode output = found.get();
            assertThat(output.uri().toString()).isEqualTo("ctxfs://user/10001/docs/api.md");
            assertThat(output.type()).isEqualTo(NodeType.DOCUMENT);
            assertThat(output.parent()).isEqualTo(CtxfsUri.of("user/10001", "docs"));
            assertThat(output.metadata()).containsEntry("title", "API Guide");
        }

        @Test
        @DisplayName("重复保存覆盖更新（upsert），created_at 保留不变")
        void saveTwiceUpserts() {
            ContextNode first = node("user/10001", "notes.md", NodeType.DOCUMENT);
            repository.save(first);
            ContextNode updated =
                    first.withMetadata(
                            Map.of("revised", "true"), first.createdAt().plusSeconds(60));
            repository.save(updated);

            Optional<ContextNode> found = repository.findByUri(first.uri());
            assertThat(found).isPresent();
            assertThat(found.get().metadata()).containsEntry("revised", "true");
            assertThat(found.get().createdAt()).isEqualTo(first.createdAt());
        }

        @Test
        @DisplayName("查询不存在的 URI 返回 empty，exists 为 false")
        void missingNode() {
            CtxfsUri uri = CtxfsUri.of("agent/42", "missing.md");
            assertThat(repository.findByUri(uri)).isEmpty();
            assertThat(repository.exists(uri)).isFalse();
        }

        @Test
        @DisplayName("删除节点后 levels 一并清理，子节点不级联")
        void deleteKeepsChildren() {
            ContextNode parent = node("user/10001", "temp", NodeType.DIRECTORY);
            ContextNode child = node("user/10001", "temp/child.md", NodeType.DOCUMENT);
            repository.save(parent);
            repository.save(child);
            repository.saveLevel(parent.uri(), new NodeLevel(Level.L0, "parent summary", 10));

            repository.delete(parent.uri());

            assertThat(repository.exists(parent.uri())).isFalse();
            assertThat(
                            jdbcTemplate.queryForList(
                                    "SELECT uri FROM ctx_level WHERE uri = ?",
                                    String.class,
                                    parent.uri().toString()))
                    .isEmpty();
            assertThat(repository.exists(child.uri())).isTrue();
        }
    }

    @Nested
    @DisplayName("子节点与子树")
    class TreeQueries {

        private boolean seeded;

        void seed() {
            if (seeded) {
                return;
            }
            repository.save(node("user/20002", "", NodeType.DIRECTORY));
            repository.save(node("user/20002", "docs", NodeType.DIRECTORY));
            repository.save(node("user/20002", "docs/api.md", NodeType.DOCUMENT));
            repository.save(node("user/20002", "docs/nested", NodeType.DIRECTORY));
            repository.save(node("user/20002", "docs/nested/deep.md", NodeType.DOCUMENT));
            seeded = true;
        }

        @Test
        @DisplayName("listChildren 仅返回直接子节点并按名称排序")
        void listChildrenSorted() {
            seed();
            List<ContextNode> children = repository.listChildren(CtxfsUri.of("user/20002", "docs"));

            assertThat(children)
                    .extracting(n -> n.uri().name())
                    .containsExactly("api.md", "nested");
        }

        @Test
        @DisplayName("listSubtree 递归 CTE：maxDepth=0 仅返回 root")
        void subtreeRootOnly() {
            seed();
            List<ContextNode> subtree =
                    repository.listSubtree(CtxfsUri.of("user/20002", "docs"), 0);

            assertThat(subtree).hasSize(1);
            assertThat(subtree.get(0).uri()).isEqualTo(CtxfsUri.of("user/20002", "docs"));
        }

        @Test
        @DisplayName("listSubtree 按深度限制展开，按 path 排序")
        void subtreeWithDepthLimit() {
            seed();
            List<ContextNode> depthOne =
                    repository.listSubtree(CtxfsUri.of("user/20002", "docs"), 1);
            assertThat(depthOne)
                    .extracting(n -> n.uri().path())
                    .containsExactly("docs", "docs/api.md", "docs/nested");

            List<ContextNode> all = repository.listSubtree(CtxfsUri.of("user/20002", "docs"), 8);
            assertThat(all)
                    .extracting(n -> n.uri().path())
                    .containsExactly("docs", "docs/api.md", "docs/nested", "docs/nested/deep.md");
        }

        @Test
        @DisplayName("同 scope 根节点下的子节点通过 parent_uri 关联")
        void scopeRootChildren() {
            seed();
            List<ContextNode> children = repository.listChildren(CtxfsUri.of("user/20002", ""));

            assertThat(children).extracting(n -> n.uri().path()).containsExactly("docs");
        }
    }

    @Nested
    @DisplayName("层级内容")
    class LevelContent {

        @Test
        @DisplayName("saveLevel 插入与覆盖，findLevel 按 (uri, level) 读取")
        void levelUpsertAndFind() {
            CtxfsUri uri = CtxfsUri.of("user/30003", "guide.md");
            repository.save(node("user/30003", "guide.md", NodeType.DOCUMENT));

            repository.saveLevel(uri, new NodeLevel(Level.L0, "short summary", 20));
            repository.saveLevel(uri, new NodeLevel(Level.L1, "overview content", 800));

            Optional<NodeLevel> l0 = repository.findLevel(uri, Level.L0);
            assertThat(l0).hasValue(new NodeLevel(Level.L0, "short summary", 20));

            repository.saveLevel(uri, new NodeLevel(Level.L0, "revised summary", 25));
            assertThat(repository.findLevel(uri, Level.L0))
                    .hasValue(new NodeLevel(Level.L0, "revised summary", 25));
            assertThat(repository.findLevel(uri, Level.L2)).isEmpty();
        }
    }

    @Nested
    @DisplayName("scope 隔离")
    class ScopeIsolation {

        @Test
        @DisplayName("不同 scope 下同名路径是不同节点，互不可见")
        void samePathDifferentScope() {
            ContextNode userNode = node("user/40004", "profile.md", NodeType.MEMORY);
            ContextNode agentNode = node("agent/40004", "profile.md", NodeType.MEMORY);
            repository.save(userNode);
            repository.save(agentNode);

            assertThat(repository.findByUri(userNode.uri())).isPresent();
            assertThat(repository.findByUri(agentNode.uri())).isPresent();
            assertThat(repository.listChildren(CtxfsUri.of("user/40004", "")))
                    .extracting(n -> n.uri().toString())
                    .containsExactly("ctxfs://user/40004/profile.md");
        }
    }
}
