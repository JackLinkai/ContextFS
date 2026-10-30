package com.contextfs.core.model;

import com.contextfs.core.uri.CtxfsUri;
import java.time.Instant;
import java.util.Map;

/**
 * 上下文节点：挂载在 {@code ctxfs://{scope}/{path}} 树上的领域对象。
 *
 * @param uri       节点 URI
 * @param type      节点类型
 * @param parent    父节点 URI，根节点为 null
 * @param metadata  元数据
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record ContextNode(
        CtxfsUri uri,
        NodeType type,
        CtxfsUri parent,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt) {

    public ContextNode {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** 创建新节点，时间戳统一取 now。 */
    public static ContextNode create(CtxfsUri uri, NodeType type, Instant now) {
        return new ContextNode(uri, type, uri.parent().orElse(null), Map.of(), now, now);
    }

    /** 返回带新元数据的副本，updatedAt 更新为 now。 */
    public ContextNode withMetadata(Map<String, String> newMetadata, Instant now) {
        return new ContextNode(uri, type, parent, newMetadata, createdAt, now);
    }
}
