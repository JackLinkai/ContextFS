package com.contextfs.retrieval.support;

import com.contextfs.core.port.EmbeddingClient;
import java.util.Locale;

/**
 * 测试与评测用嵌入客户端：字符 unigram + bigram 哈希到固定维度并做 L2 归一化。
 *
 * <p>无需外部服务；对共享词汇（中文 bigram / 英文单词片段）的文本给出较高余弦相似度， 供单元测试、检索质量评测与离线开发复用，不追求真实语义效果。
 */
public final class HashingEmbeddingClient implements EmbeddingClient {

    public static final int DIMENSION = 128;

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSION];
        if (text == null || text.isEmpty()) {
            return vector;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < normalized.length(); i++) {
            add(vector, normalized.substring(i, i + 1));
            if (i + 1 < normalized.length()) {
                add(vector, normalized.substring(i, i + 2));
            }
        }
        float norm = 0f;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0f) {
            for (int i = 0; i < DIMENSION; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    private void add(float[] vector, String gram) {
        vector[Math.floorMod(gram.hashCode(), DIMENSION)] += 1f;
    }
}
