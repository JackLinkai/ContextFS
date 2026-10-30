package com.contextfs.storage.milvus;

import com.contextfs.core.model.Level;
import com.contextfs.core.port.VectorMatch;
import com.contextfs.core.port.VectorRecord;
import com.contextfs.core.port.VectorStore;
import com.contextfs.core.uri.CtxfsUri;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.partition.request.CreatePartitionReq;
import io.milvus.v2.service.partition.request.HasPartitionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** {@link VectorStore} 的 Milvus 适配实现：集合按 scope 分区，HNSW + 余弦相似度。 */
public class MilvusVectorStore implements VectorStore, AutoCloseable {

    public static final String COLLECTION_NAME = "ctxfs_chunks";

    private static final String VECTOR_FIELD = "embedding";
    private static final List<String> OUTPUT_FIELDS = List.of("uri", "level", "content", "scope");

    private final MilvusClientV2 client;
    private final int dimension;
    private volatile boolean collectionReady;

    /**
     * @param milvusUri 如 {@code http://localhost:19530}
     */
    public MilvusVectorStore(String milvusUri, int dimension) {
        this(new MilvusClientV2(ConnectConfig.builder().uri(milvusUri).build()), dimension);
    }

    MilvusVectorStore(MilvusClientV2 client, int dimension) {
        this.client = Objects.requireNonNull(client, "client");
        this.dimension = dimension;
    }

    @Override
    public void upsert(VectorRecord record) {
        Objects.requireNonNull(record, "record");
        ensureCollection();
        JsonObject row = new JsonObject();
        row.addProperty("id", primaryKey(record.uri(), record.level()));
        row.addProperty("uri", record.uri().toString());
        row.addProperty("level", record.level().name());
        row.addProperty("scope", record.uri().scope());
        row.addProperty("content", record.content());
        JsonArray embedding = new JsonArray(record.embedding().length);
        for (float value : record.embedding()) {
            embedding.add(new JsonPrimitive(value));
        }
        row.add(VECTOR_FIELD, embedding);
        client.upsert(
                UpsertReq.builder()
                        .collectionName(COLLECTION_NAME)
                        .partitionName(ensurePartition(record.uri().scope()))
                        .data(List.of(row))
                        .build());
    }

    @Override
    public List<VectorMatch> search(float[] queryEmbedding, String scope, Level level, int topK) {
        ensureCollection();
        SearchReq.SearchReqBuilder<?, ?> request =
                SearchReq.builder()
                        .collectionName(COLLECTION_NAME)
                        .annsField(VECTOR_FIELD)
                        .data(List.of(new FloatVec(queryEmbedding)))
                        .topK(topK)
                        .outputFields(OUTPUT_FIELDS);
        String filter = buildFilter(scope, level);
        if (!filter.isEmpty()) {
            request.filter(filter);
        }
        SearchResp response = client.search(request.build());
        List<VectorMatch> matches = new ArrayList<>();
        for (SearchResp.SearchResult hit : response.getSearchResults().get(0)) {
            Map<String, Object> entity = hit.getEntity();
            matches.add(
                    new VectorMatch(
                            CtxfsUri.parse(String.valueOf(entity.get("uri"))),
                            Level.valueOf(String.valueOf(entity.get("level"))),
                            String.valueOf(entity.get("content")),
                            hit.getScore()));
        }
        return matches;
    }

    @Override
    public void deleteByUri(CtxfsUri uri) {
        Objects.requireNonNull(uri, "uri");
        ensureCollection();
        client.delete(
                DeleteReq.builder()
                        .collectionName(COLLECTION_NAME)
                        .filter("uri == \"" + escapeExpr(uri.toString()) + "\"")
                        .build());
    }

    @Override
    public void close() {
        client.close();
    }

    /** 首次访问时惰性建集合，避免构造期强依赖 Milvus 可用。 */
    private void ensureCollection() {
        if (collectionReady) {
            return;
        }
        synchronized (this) {
            if (collectionReady) {
                return;
            }
            if (!Boolean.TRUE.equals(
                    client.hasCollection(
                            HasCollectionReq.builder().collectionName(COLLECTION_NAME).build()))) {
                client.createCollection(
                        CreateCollectionReq.builder()
                                .collectionName(COLLECTION_NAME)
                                .collectionSchema(collectionSchema())
                                .indexParams(List.of(indexParams()))
                                .build());
            }
            collectionReady = true;
        }
    }

    private CreateCollectionReq.CollectionSchema collectionSchema() {
        CreateCollectionReq.CollectionSchema schema =
                CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(
                AddFieldReq.builder()
                        .fieldName("id")
                        .dataType(DataType.VarChar)
                        .isPrimaryKey(true)
                        .maxLength(65535)
                        .build());
        schema.addField(
                AddFieldReq.builder()
                        .fieldName("uri")
                        .dataType(DataType.VarChar)
                        .maxLength(65535)
                        .build());
        schema.addField(
                AddFieldReq.builder()
                        .fieldName("level")
                        .dataType(DataType.VarChar)
                        .maxLength(8)
                        .build());
        schema.addField(
                AddFieldReq.builder()
                        .fieldName("scope")
                        .dataType(DataType.VarChar)
                        .maxLength(512)
                        .build());
        schema.addField(
                AddFieldReq.builder()
                        .fieldName("content")
                        .dataType(DataType.VarChar)
                        .maxLength(65535)
                        .build());
        schema.addField(
                AddFieldReq.builder()
                        .fieldName(VECTOR_FIELD)
                        .dataType(DataType.FloatVector)
                        .dimension(dimension)
                        .build());
        return schema;
    }

    private IndexParam indexParams() {
        Map<String, Object> hnswParams = new HashMap<>();
        hnswParams.put("M", 16);
        hnswParams.put("efConstruction", 200);
        return IndexParam.builder()
                .fieldName(VECTOR_FIELD)
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(hnswParams)
                .build();
    }

    private String ensurePartition(String scope) {
        String partition = partitionName(scope);
        if (!Boolean.TRUE.equals(
                client.hasPartition(
                        HasPartitionReq.builder()
                                .collectionName(COLLECTION_NAME)
                                .partitionName(partition)
                                .build()))) {
            client.createPartition(
                    CreatePartitionReq.builder()
                            .collectionName(COLLECTION_NAME)
                            .partitionName(partition)
                            .build());
        }
        return partition;
    }

    /** 分区名只能包含字母数字与下划线，scope 中的 '/' 替换为 '_'。 */
    static String partitionName(String scope) {
        return "p_" + scope.replace('/', '_');
    }

    /** 主键：同一 URI 同一层级仅保留最新一条向量。 */
    static String primaryKey(CtxfsUri uri, Level level) {
        return uri + "#" + level.name();
    }

    static String buildFilter(String scope, Level level) {
        List<String> conditions = new ArrayList<>();
        if (scope != null) {
            conditions.add("scope == \"" + escapeExpr(scope) + "\"");
        }
        if (level != null) {
            conditions.add("level == \"" + level.name() + "\"");
        }
        return String.join(" && ", conditions);
    }

    static String escapeExpr(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
