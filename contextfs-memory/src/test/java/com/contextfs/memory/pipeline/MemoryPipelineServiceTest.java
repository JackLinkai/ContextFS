package com.contextfs.memory.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.contextfs.core.inmem.InMemoryNodeRepository;
import com.contextfs.core.inmem.InMemoryVectorStore;
import com.contextfs.core.model.ContextNode;
import com.contextfs.core.model.Level;
import com.contextfs.core.model.NodeLevel;
import com.contextfs.core.model.NodeType;
import com.contextfs.core.uri.CtxfsUri;
import com.contextfs.observability.log.InMemoryTraceLogStore;
import com.contextfs.observability.log.ToolCallLogEntry;
import com.contextfs.retrieval.engine.RetrievalConfig;
import com.contextfs.retrieval.engine.RetrievalMode;
import com.contextfs.retrieval.engine.RetrievalResult;
import com.contextfs.retrieval.engine.RetrievalService;
import com.contextfs.retrieval.support.HashingEmbeddingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** {@link MemoryPipelineService} 单元测试：InMemory 装配 + 哈希嵌入，不依赖外部服务。 */
@DisplayName("MemoryPipelineService 记忆管线")
class MemoryPipelineServiceTest {

    private InMemoryNodeRepository repository;
    private InMemoryVectorStore vectorStore;
    private HashingEmbeddingClient embeddingClient;
    private MemoryPipelineService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryNodeRepository();
        vectorStore = new InMemoryVectorStore();
        embeddingClient = new HashingEmbeddingClient();
        service =
                new MemoryPipelineService(
                        repository,
                        embeddingClient,
                        vectorStore,
                        new MemoryExtractor(),
                        true,
                        MemoryPipelineService.SYNC_EXECUTOR);
    }

    private static final List<SessionMessage> PREFERENCE_MESSAGES =
            List.of(
                    new SessionMessage("user", "以后回答尽量简洁，代码示例优先使用 Java。"),
                    new SessionMessage("assistant", "好的，我会保持简洁并优先使用 Java。"));

    private Optional<ContextNode> node(String scope, String path) {
        return repository.findByUri(CtxfsUri.of(scope, path));
    }

    private Optional<NodeLevel> level(String scope, String path, Level level) {
        return repository.findLevel(CtxfsUri.of(scope, path), level);
    }

    @Nested
    @DisplayName("会话归档")
    class Archive {

        @Test
        @DisplayName("会话消息落库为 ctxfs://session/{id}/archive.md DOCUMENT 节点")
        void archivesSessionAsDocument() {
            CommitOutcome outcome = service.commit("sess_1", "coder", "10001", PREFERENCE_MESSAGES);

            assertThat(outcome.archivedUri()).isEqualTo("ctxfs://session/sess_1/archive.md");
            assertThat(node("session/sess_1", "archive.md"))
                    .hasValueSatisfying(n -> assertThat(n.type()).isEqualTo(NodeType.DOCUMENT));
            assertThat(level("session/sess_1", "archive.md", Level.L2))
                    .hasValueSatisfying(
                            l -> {
                                assertThat(l.content()).contains("user: 以后回答尽量简洁");
                                assertThat(l.content()).contains("assistant: 好的，我会保持简洁并优先使用 Java。");
                            });
        }

        @Test
        @DisplayName("enabled=false 时仅归档，不提取记忆")
        void disabledOnlyArchives() {
            MemoryPipelineService disabled =
                    new MemoryPipelineService(
                            repository,
                            embeddingClient,
                            vectorStore,
                            new MemoryExtractor(),
                            false,
                            MemoryPipelineService.SYNC_EXECUTOR);

            CommitOutcome outcome =
                    disabled.commit("sess_2", "coder", "10001", PREFERENCE_MESSAGES);

            assertThat(outcome.changes()).isEmpty();
            assertThat(node("session/sess_2", "archive.md")).isPresent();
            assertThat(node("user/10001", "memories/preference-1.md")).isEmpty();
        }
    }

    @Nested
    @DisplayName("记忆提取与合并决策")
    class ExtractAndMerge {

        @Test
        @DisplayName("偏好消息新建 MEMORY 节点到 user/{id}/memories 下")
        void createsPreferenceMemory() {
            CommitOutcome outcome = service.commit("sess_1", "coder", "10001", PREFERENCE_MESSAGES);

            assertThat(outcome.changes()).hasSize(1);
            MemoryChange change = outcome.changes().get(0);
            assertThat(change.decision()).isEqualTo(MergeDecision.CREATED);
            assertThat(change.type()).isEqualTo(MemoryType.PREFERENCE);
            assertThat(change.uri()).isEqualTo("ctxfs://user/10001/memories/preference-1.md");
            assertThat(node("user/10001", "memories/preference-1.md"))
                    .hasValueSatisfying(n -> assertThat(n.type()).isEqualTo(NodeType.MEMORY));
            assertThat(level("user/10001", "memories/preference-1.md", Level.L2))
                    .hasValueSatisfying(l -> assertThat(l.content()).contains("尽量简洁"));
        }

        @Test
        @DisplayName("内容重复的重复提交被跳过，不产生新节点")
        void duplicateCommitSkips() {
            service.commit("sess_1", "coder", "10001", PREFERENCE_MESSAGES);
            CommitOutcome second = service.commit("sess_2", "coder", "10001", PREFERENCE_MESSAGES);

            assertThat(second.changes().get(0).decision()).isEqualTo(MergeDecision.SKIPPED);
            assertThat(node("user/10001", "memories/preference-2.md")).isEmpty();
        }

        @Test
        @DisplayName("高相似度新信息合并进已有记忆节点")
        void similarContentMerges() {
            service.commit("sess_1", "coder", "10001", PREFERENCE_MESSAGES);
            List<SessionMessage> refined =
                    List.of(new SessionMessage("user", "以后回答尽量简洁，代码示例优先使用 Java 17。"));

            CommitOutcome outcome = service.commit("sess_2", "coder", "10001", refined);

            assertThat(outcome.changes().get(0).decision()).isEqualTo(MergeDecision.MERGED);
            assertThat(outcome.changes().get(0).uri())
                    .isEqualTo("ctxfs://user/10001/memories/preference-1.md");
            assertThat(level("user/10001", "memories/preference-1.md", Level.L2))
                    .hasValueSatisfying(
                            l -> {
                                assertThat(l.content()).contains("优先使用 Java。");
                                assertThat(l.content()).contains("优先使用 Java 17。");
                            });
        }

        @Test
        @DisplayName("中等相似度视为已被覆盖，跳过")
        void partialOverlapSkips() {
            service.commit(
                    "sess_1", "coder", "10001", List.of(new SessionMessage("user", "用户偏好暗色主题界面。")));
            CommitOutcome outcome =
                    service.commit(
                            "sess_2",
                            "coder",
                            "10001",
                            List.of(new SessionMessage("user", "用户偏好亮色主题的界面。")));

            assertThat(outcome.changes().get(0).decision()).isEqualTo(MergeDecision.SKIPPED);
            assertThat(node("user/10001", "memories/preference-2.md")).isEmpty();
        }

        @Test
        @DisplayName("不相关内容新建独立记忆文件，按类型编号")
        void differentContentCreatesNewFile() {
            service.commit("sess_1", "coder", "10001", PREFERENCE_MESSAGES);
            CommitOutcome outcome =
                    service.commit(
                            "sess_2",
                            "coder",
                            "10001",
                            List.of(new SessionMessage("user", "我们的项目数据库使用 PostgreSQL 部署。")));

            assertThat(outcome.changes().get(0).decision()).isEqualTo(MergeDecision.CREATED);
            assertThat(outcome.changes().get(0).type()).isEqualTo(MemoryType.FACT);
            assertThat(outcome.changes().get(0).uri())
                    .isEqualTo("ctxfs://user/10001/memories/fact-1.md");
        }

        @Test
        @DisplayName("目标 scope 优先级：userId > agentId > global")
        void targetScopePriority() {
            service.commit("sess_1", null, "10001", PREFERENCE_MESSAGES);
            service.commit("sess_2", "coder", null, PREFERENCE_MESSAGES);
            service.commit("sess_3", null, null, PREFERENCE_MESSAGES);

            assertThat(node("user/10001", "memories/preference-1.md")).isPresent();
            assertThat(node("agent/coder", "memories/preference-1.md")).isPresent();
            assertThat(node("global", "memories/preference-1.md")).isPresent();
        }
    }

    @Nested
    @DisplayName("与检索引擎集成（验收）")
    class RetrievalIntegration {

        private RetrievalService retrievalService() {
            return new RetrievalService(
                    repository,
                    vectorStore,
                    embeddingClient,
                    new RetrievalConfig(5, Level.L1, 0.2, 4096, 3));
        }

        @Test
        @DisplayName("commit 生成的 MEMORY 节点可被检索命中")
        void memoryNodeRetrievable() {
            service.commit("sess_1", "coder", "10001", PREFERENCE_MESSAGES);

            RetrievalResult result =
                    retrievalService()
                            .retrieve(
                                    "user/10001",
                                    "回答尽量简洁 代码示例 Java",
                                    RetrievalMode.SEARCH,
                                    null,
                                    "trace-1");

            List<String> uris =
                    result.items().stream()
                            .map(com.contextfs.retrieval.engine.RetrievalItem::uri)
                            .toList();
            assertThat(uris).contains("ctxfs://user/10001/memories/preference-1.md");
        }

        @Test
        @DisplayName("scope 隔离：其他用户检索不到该记忆")
        void memoryIsolatedByScope() {
            service.commit("sess_1", "coder", "10001", PREFERENCE_MESSAGES);

            RetrievalResult other =
                    retrievalService()
                            .retrieve(
                                    "user/10002",
                                    "回答尽量简洁 代码示例 Java",
                                    RetrievalMode.SEARCH,
                                    null,
                                    "trace-2");
            RetrievalResult owner =
                    retrievalService()
                            .retrieve(
                                    "user/10001",
                                    "回答尽量简洁 代码示例 Java",
                                    RetrievalMode.SEARCH,
                                    null,
                                    "trace-3");

            assertThat(other.items()).isEmpty();
            assertThat(owner.items()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("词法相似度")
    class Similarity {

        @Test
        @DisplayName("相同文本为 1，无关文本接近 0")
        void lexicalSimilarityBoundaries() {
            assertThat(MemoryPipelineService.lexicalSimilarity("用户偏好暗色主题", "用户偏好暗色主题"))
                    .isEqualTo(1.0);
            assertThat(MemoryPipelineService.lexicalSimilarity("用户偏好暗色主题", "数据库部署在云端"))
                    .isLessThan(0.2);
            assertThat(MemoryPipelineService.lexicalSimilarity(null, "任意")).isZero();
        }
    }

    @Nested
    @DisplayName("可观测（tool_call_log）")
    class Observability {

        @Test
        @DisplayName("commit 后写入提取与决策记录，traceId 贯穿")
        void writesToolCallLogs() {
            InMemoryTraceLogStore store = new InMemoryTraceLogStore();
            MemoryPipelineService observed =
                    new MemoryPipelineService(
                            repository,
                            embeddingClient,
                            vectorStore,
                            new MemoryExtractor(),
                            true,
                            MemoryPipelineService.SYNC_EXECUTOR,
                            store,
                            new ObjectMapper());
            observed.commit("sess-obs", "coder", "10001", PREFERENCE_MESSAGES, "trace-mem-001");

            List<ToolCallLogEntry> logs = store.toolCallLogs("trace-mem-001");
            assertThat(logs).hasSize(2);
            assertThat(logs.get(0).tool()).isEqualTo(MemoryPipelineService.TOOL_EXTRACT);
            assertThat(logs.get(0).sessionId()).isEqualTo("sess-obs");
            assertThat(logs.get(0).agentId()).isEqualTo("coder");
            assertThat(logs.get(0).output()).contains("PREFERENCE");
            assertThat(logs.get(0).spanId()).hasSize(16);
            assertThat(logs.get(1).tool()).isEqualTo(MemoryPipelineService.TOOL_DECIDE);
            assertThat(logs.get(1).output()).contains("CREATED");
            assertThat(logs.get(1).status()).isEqualTo("OK");
            assertThat(logs.get(1).latencyMs()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("enabled=false 仅归档，不写 tool_call_log")
        void disabledPipelineWritesNoToolCallLogs() {
            InMemoryTraceLogStore store = new InMemoryTraceLogStore();
            MemoryPipelineService disabled =
                    new MemoryPipelineService(
                            repository,
                            embeddingClient,
                            vectorStore,
                            new MemoryExtractor(),
                            false,
                            MemoryPipelineService.SYNC_EXECUTOR,
                            store,
                            new ObjectMapper());
            disabled.commit("sess-off", "coder", "10001", PREFERENCE_MESSAGES, "trace-off");

            assertThat(store.toolCallLogs("trace-off")).isEmpty();
            assertThat(
                            disabled.commit("sess-off2", "coder", "10001", PREFERENCE_MESSAGES)
                                    .archivedUri())
                    .isEqualTo("ctxfs://session/sess-off2/archive.md");
        }
    }
}
