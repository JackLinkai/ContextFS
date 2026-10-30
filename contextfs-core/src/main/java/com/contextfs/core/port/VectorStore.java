package com.contextfs.core.port;

import com.contextfs.core.model.Level;
import com.contextfs.core.uri.CtxfsUri;
import java.util.List;

/**
 * 向量检索端口，由 Milvus 适配实现。
 *
 * <p>调用方负责先通过嵌入模型将查询文本向量化。
 */
public interface VectorStore {

    void upsert(VectorRecord record);

    /**
     * 在指定 scope 与层级内检索最相似的 topK 条记录。
     *
     * @param queryEmbedding 查询向量
     * @param scope          scope 过滤，如 {@code global}、{@code user/10001}；null 表示不过滤
     * @param level          层级过滤，null 表示不过滤
     * @param topK           返回条数上限
     */
    List<VectorMatch> search(float[] queryEmbedding, String scope, Level level, int topK);

    void deleteByUri(CtxfsUri uri);
}
