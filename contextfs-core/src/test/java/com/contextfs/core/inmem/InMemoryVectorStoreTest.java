package com.contextfs.core.inmem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.contextfs.core.model.Level;
import com.contextfs.core.port.VectorMatch;
import com.contextfs.core.port.VectorRecord;
import com.contextfs.core.uri.CtxfsUri;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** {@link InMemoryVectorStore} 单元测试。 */
class InMemoryVectorStoreTest {

    private static final float[] V1 = {1f, 0f, 0f};
    private static final float[] V2 = {0f, 1f, 0f};

    private VectorRecord record(String uri, String scope, Level level, float[] embedding) {
        return new VectorRecord(CtxfsUri.of(scope, uri), level, "content-" + uri, embedding);
    }

    @Nested
    @DisplayName("upsert")
    class Upsert {

        @Test
        @DisplayName("同一 uri + level 覆盖旧记录")
        void upsertOverwritesSameUriAndLevel() {
            InMemoryVectorStore store = new InMemoryVectorStore();
            store.upsert(record("a.md", "global", Level.L0, V1));
            store.upsert(new VectorRecord(CtxfsUri.of("global", "a.md"), Level.L0, "updated", V1));

            List<VectorMatch> hits = store.search(V1, "global", Level.L0, 10);
            assertThat(hits).hasSize(1);
            assertThat(hits.get(0).content()).isEqualTo("updated");
        }

        @Test
        @DisplayName("同一 uri 不同 level 各自保留")
        void upsertKeepsDifferentLevels() {
            InMemoryVectorStore store = new InMemoryVectorStore();
            store.upsert(record("a.md", "global", Level.L0, V1));
            store.upsert(record("a.md", "global", Level.L2, V1));

            assertThat(store.search(V1, null, null, 10)).hasSize(2);
        }
    }

    @Nested
    @DisplayName("search")
    class Search {

        @Test
        @DisplayName("按余弦相似度降序返回 topK")
        void searchOrdersByScoreDesc() {
            InMemoryVectorStore store = new InMemoryVectorStore();
            store.upsert(record("match.md", "global", Level.L0, V1));
            store.upsert(record("other.md", "global", Level.L0, V2));

            List<VectorMatch> hits = store.search(V1, null, null, 10);
            assertThat(hits).hasSize(2);
            assertThat(hits.get(0).uri().path()).isEqualTo("match.md");
            assertThat(hits.get(0).score()).isCloseTo(1f, within(0.0001f));
            assertThat(hits.get(1).score()).isCloseTo(0f, within(0.0001f));
        }

        @Test
        @DisplayName("topK 截断结果条数")
        void searchTruncatesToTopK() {
            InMemoryVectorStore store = new InMemoryVectorStore();
            store.upsert(record("a.md", "global", Level.L0, V1));
            store.upsert(record("b.md", "global", Level.L0, V1));
            store.upsert(record("c.md", "global", Level.L0, V1));

            assertThat(store.search(V1, null, null, 2)).hasSize(2);
        }

        @Test
        @DisplayName("scope 过滤：不返回其他 scope 的记录")
        void searchFiltersByScope() {
            InMemoryVectorStore store = new InMemoryVectorStore();
            store.upsert(record("a.md", "global", Level.L0, V1));
            store.upsert(record("b.md", "user/10001", Level.L0, V1));

            List<VectorMatch> hits = store.search(V1, "user/10001", null, 10);
            assertThat(hits).hasSize(1);
            assertThat(hits.get(0).uri().scope()).isEqualTo("user/10001");
        }

        @Test
        @DisplayName("level 过滤")
        void searchFiltersByLevel() {
            InMemoryVectorStore store = new InMemoryVectorStore();
            store.upsert(record("a.md", "global", Level.L0, V1));
            store.upsert(record("a.md", "global", Level.L2, V1));

            List<VectorMatch> hits = store.search(V1, null, Level.L2, 10);
            assertThat(hits).hasSize(1);
            assertThat(hits.get(0).level()).isEqualTo(Level.L2);
        }

        @Test
        @DisplayName("零向量查询返回相似度 0，不抛异常")
        void searchWithZeroVector() {
            InMemoryVectorStore store = new InMemoryVectorStore();
            store.upsert(record("a.md", "global", Level.L0, V1));

            List<VectorMatch> hits = store.search(new float[] {0f, 0f, 0f}, null, null, 10);
            assertThat(hits).hasSize(1);
            assertThat(hits.get(0).score()).isZero();
        }

        @Test
        @DisplayName("topK<=0 返回空结果")
        void searchWithNonPositiveTopK() {
            InMemoryVectorStore store = new InMemoryVectorStore();
            store.upsert(record("a.md", "global", Level.L0, V1));

            assertThat(store.search(V1, null, null, 0)).isEmpty();
            assertThat(store.search(V1, null, null, -1)).isEmpty();
        }

        @Test
        @DisplayName("维度不一致的记录被跳过")
        void searchSkipsDimensionMismatch() {
            InMemoryVectorStore store = new InMemoryVectorStore();
            store.upsert(
                    new VectorRecord(
                            CtxfsUri.of("global", "a.md"), Level.L0, "c", new float[] {1f, 2f}));

            assertThat(store.search(V1, null, null, 10)).isEmpty();
        }
    }

    @Nested
    @DisplayName("deleteByUri")
    class Delete {

        @Test
        @DisplayName("删除节点全部层级的记录")
        void deleteRemovesAllLevels() {
            InMemoryVectorStore store = new InMemoryVectorStore();
            store.upsert(record("a.md", "global", Level.L0, V1));
            store.upsert(record("a.md", "global", Level.L2, V1));
            store.upsert(record("b.md", "global", Level.L0, V1));

            store.deleteByUri(CtxfsUri.of("global", "a.md"));

            List<VectorMatch> hits = store.search(V1, null, null, 10);
            assertThat(hits).hasSize(1);
            assertThat(hits.get(0).uri().path()).isEqualTo("b.md");
        }
    }
}
