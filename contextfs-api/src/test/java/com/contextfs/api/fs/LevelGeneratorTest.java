package com.contextfs.api.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.contextfs.core.inmem.InMemoryCacheStore;
import com.contextfs.core.inmem.InMemoryNodeRepository;
import com.contextfs.core.model.Level;
import com.contextfs.core.model.NodeType;
import com.contextfs.core.port.EmbeddingClient;
import com.contextfs.core.port.LlmClient;
import com.contextfs.core.port.VectorMatch;
import com.contextfs.core.port.VectorRecord;
import com.contextfs.core.port.VectorStore;
import com.contextfs.core.uri.CtxfsUri;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LevelGenerator 三层内容生成")
class LevelGeneratorTest {

    private InMemoryNodeRepository repository;
    private InMemoryCacheStore cache;

    @BeforeEach
    void setUp() {
        repository = new InMemoryNodeRepository();
        cache = new InMemoryCacheStore();
    }

    private LevelGenerator generator(LlmClient llm, EmbeddingClient embedding, VectorStore vector) {
        return new LevelGenerator(repository, cache, llm, embedding, vector, Runnable::run);
    }

    @Nested
    @DisplayName("摘要生成")
    class Summaries {

        @Test
        @DisplayName("文档节点生成 L0/L1 并落库")
        void generateDocumentLevels() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://global/docs/java.md");
            FakeLlmClient llm = new FakeLlmClient();

            generator(llm, null, null).generateNow(uri, "并发编程要点全文", NodeType.DOCUMENT);

            assertThat(repository.findLevel(uri, Level.L0).orElseThrow().content())
                    .isEqualTo("【L0】并发编程要点全文");
            assertThat(repository.findLevel(uri, Level.L1).orElseThrow().content())
                    .isEqualTo("【L1】并发编程要点全文");
            assertThat(repository.findLevel(uri, Level.L0).orElseThrow().tokenCount()).isPositive();
        }

        @Test
        @DisplayName("目录节点由子节点 L0 归纳聚合生成")
        void aggregateDirectoryFromChildren() {
            CtxfsUri dir = CtxfsUri.parse("ctxfs://global/docs");
            CtxfsUri child = CtxfsUri.parse("ctxfs://global/docs/java.md");
            repository.save(
                    com.contextfs.core.model.ContextNode.create(
                            child, NodeType.DOCUMENT, java.time.Instant.now()));
            repository.saveLevel(
                    child, new com.contextfs.core.model.NodeLevel(Level.L0, "并发要点", 4));
            FakeLlmClient llm = new FakeLlmClient();

            generator(llm, null, null).generateNow(dir, null, NodeType.DIRECTORY);

            assertThat(repository.findLevel(dir, Level.L0).orElseThrow().content())
                    .isEqualTo("【L0】java.md: 并发要点\n");
            assertThat(llm.materials).anyMatch(material -> material.contains("java.md"));
        }

        @Test
        @DisplayName("无子节点的目录跳过生成")
        void emptyDirectorySkipped() {
            CtxfsUri dir = CtxfsUri.parse("ctxfs://global/empty");
            generator(new FakeLlmClient(), null, null).generateNow(dir, null, NodeType.DIRECTORY);
            assertThat(repository.findLevel(dir, Level.L0)).isEmpty();
        }
    }

    @Nested
    @DisplayName("向量化")
    class Vectors {

        @Test
        @DisplayName("L2 向量写入不依赖 LLM")
        void l2VectorWithoutLlm() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://global/docs/a.md");
            RecordingVectorStore vector = new RecordingVectorStore();

            generator(null, new FakeEmbeddingClient(), vector)
                    .generateNow(uri, "原文", NodeType.DOCUMENT);

            assertThat(vector.records).hasSize(1);
            assertThat(vector.records.get(0).level()).isEqualTo(Level.L2);
            assertThat(repository.findLevel(uri, Level.L0)).isEmpty();
        }

        @Test
        @DisplayName("LLM 可用时三层向量齐写（目录节点除外 L2）")
        void allThreeLevelsVectorized() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://global/docs/a.md");
            RecordingVectorStore vector = new RecordingVectorStore();

            generator(new FakeLlmClient(), new FakeEmbeddingClient(), vector)
                    .generateNow(uri, "原文", NodeType.DOCUMENT);

            assertThat(vector.records)
                    .extracting(VectorRecord::level)
                    .containsExactlyInAnyOrder(Level.L2, Level.L0, Level.L1);
        }

        @Test
        @DisplayName("向量库故障时降级，不影响摘要落库")
        void vectorFailureDegraded() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://global/docs/a.md");
            VectorStore brokenVector =
                    new VectorStore() {
                        @Override
                        public void upsert(VectorRecord record) {
                            throw new IllegalStateException("milvus down");
                        }

                        @Override
                        public List<VectorMatch> search(
                                float[] q, String scope, Level level, int topK) {
                            return List.of();
                        }

                        @Override
                        public void deleteByUri(CtxfsUri uri) {}
                    };

            assertThatCode(
                            () ->
                                    generator(
                                                    new FakeLlmClient(),
                                                    new FakeEmbeddingClient(),
                                                    brokenVector)
                                            .generateNow(uri, "原文", NodeType.DOCUMENT))
                    .doesNotThrowAnyException();
            assertThat(repository.findLevel(uri, Level.L0)).isPresent();
        }
    }

    @Nested
    @DisplayName("失败降级与缓存失效")
    class Resilience {

        @Test
        @DisplayName("LLM 异常时保留占位且不抛出，L2 向量仍写入")
        void llmFailureDegraded() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://global/docs/a.md");
            RecordingVectorStore vector = new RecordingVectorStore();
            LlmClient brokenLlm =
                    (content, level) -> {
                        throw new IllegalStateException("llm down");
                    };

            assertThatCode(
                            () ->
                                    generator(brokenLlm, new FakeEmbeddingClient(), vector)
                                            .generateNow(uri, "原文", NodeType.DOCUMENT))
                    .doesNotThrowAnyException();

            assertThat(repository.findLevel(uri, Level.L0)).isEmpty();
            assertThat(vector.records).extracting(VectorRecord::level).containsExactly(Level.L2);
        }

        @Test
        @DisplayName("生成完成后失效该节点全部层级缓存")
        void evictsCacheAfterGeneration() {
            CtxfsUri uri = CtxfsUri.parse("ctxfs://global/docs/a.md");
            String key = FileSystemService.CACHE_READ_PREFIX + uri + ":" + Level.L0;
            cache.put(key, "旧摘要", null);

            generator(new FakeLlmClient(), null, null).generateNow(uri, "新内容", NodeType.DOCUMENT);

            assertThat(cache.get(key)).isEmpty();
            assertThat(repository.findLevel(uri, Level.L0).orElseThrow().content())
                    .isEqualTo("【L0】新内容");
        }

        @Test
        @DisplayName("子节点写入后祖先目录摘要自下而上刷新")
        void refreshAncestorDirectories() {
            CtxfsUri dir = CtxfsUri.parse("ctxfs://global/docs");
            CtxfsUri child = CtxfsUri.parse("ctxfs://global/docs/java.md");
            repository.save(
                    com.contextfs.core.model.ContextNode.create(
                            dir, NodeType.DIRECTORY, java.time.Instant.now()));
            repository.save(
                    com.contextfs.core.model.ContextNode.create(
                            child, NodeType.DOCUMENT, java.time.Instant.now()));

            generator(new FakeLlmClient(), null, null)
                    .generateNow(child, "并发全文", NodeType.DOCUMENT);

            String dirL0 = repository.findLevel(dir, Level.L0).orElseThrow().content();
            assertThat(dirL0).contains("java.md").contains("【L0】");
        }
    }

    /** 固定输出的 Fake LLM：记录每次请求的素材。 */
    static class FakeLlmClient implements LlmClient {
        final List<String> materials = new CopyOnWriteArrayList<>();

        @Override
        public String summarize(String content, Level targetLevel) {
            materials.add(content);
            return targetLevel == Level.L0 ? "【L0】" + content : "【L1】" + content;
        }
    }

    /** 按内容长度生成伪向量的 Fake 嵌入。 */
    static class FakeEmbeddingClient implements EmbeddingClient {
        @Override
        public float[] embed(String text) {
            return new float[] {text.length()};
        }
    }

    /** 记录全部 upsert 的向量库。 */
    static class RecordingVectorStore implements VectorStore {
        final List<VectorRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void upsert(VectorRecord record) {
            records.add(record);
        }

        @Override
        public List<VectorMatch> search(
                float[] queryEmbedding, String scope, Level level, int topK) {
            return List.of();
        }

        @Override
        public void deleteByUri(CtxfsUri uri) {}
    }
}
