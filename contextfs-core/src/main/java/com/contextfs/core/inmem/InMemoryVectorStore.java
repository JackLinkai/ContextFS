package com.contextfs.core.inmem;

import com.contextfs.core.model.Level;
import com.contextfs.core.port.VectorMatch;
import com.contextfs.core.port.VectorRecord;
import com.contextfs.core.port.VectorStore;
import com.contextfs.core.uri.CtxfsUri;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于内存的 {@link VectorStore} 实现：余弦相似度检索，无外部依赖。
 *
 * <p>供单元测试、检索质量评测与本地开发（未启用 Milvus 时）复用。
 */
public class InMemoryVectorStore implements VectorStore {

    private final List<VectorRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void upsert(VectorRecord record) {
        Objects.requireNonNull(record, "record");
        // 同一 uri + level 视为同一条记录，覆盖旧值
        records.removeIf(
                existing ->
                        existing.uri().equals(record.uri()) && existing.level() == record.level());
        records.add(record);
    }

    @Override
    public List<VectorMatch> search(float[] queryEmbedding, String scope, Level level, int topK) {
        Objects.requireNonNull(queryEmbedding, "queryEmbedding");
        if (topK <= 0) {
            return List.of();
        }
        List<VectorMatch> matches = new ArrayList<>();
        for (VectorRecord record : records) {
            if (scope != null && !record.uri().scope().equals(scope)) {
                continue;
            }
            if (level != null && record.level() != level) {
                continue;
            }
            if (record.embedding() == null || record.embedding().length != queryEmbedding.length) {
                continue;
            }
            matches.add(
                    new VectorMatch(
                            record.uri(),
                            record.level(),
                            record.content(),
                            cosine(queryEmbedding, record.embedding())));
        }
        matches.sort(Comparator.comparingDouble(VectorMatch::score).reversed());
        return matches.size() <= topK ? matches : new ArrayList<>(matches.subList(0, topK));
    }

    @Override
    public void deleteByUri(CtxfsUri uri) {
        Objects.requireNonNull(uri, "uri");
        records.removeIf(record -> record.uri().equals(uri));
    }

    /** 余弦相似度；任一向量为零向量时返回 0。 */
    static float cosine(float[] a, float[] b) {
        float dot = 0f;
        float normA = 0f;
        float normB = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0f || normB == 0f) {
            return 0f;
        }
        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }
}
