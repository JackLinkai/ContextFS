package com.contextfs.api.dto;

import com.contextfs.core.model.ContextNode;
import com.contextfs.core.model.NodeType;
import java.time.Instant;
import java.util.Map;

/** 节点信息 DTO。 */
public record NodeDto(
        String uri,
        String name,
        NodeType type,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt) {

    public static NodeDto from(ContextNode node) {
        return new NodeDto(
                node.uri().toString(),
                node.uri().name(),
                node.type(),
                node.metadata(),
                node.createdAt(),
                node.updatedAt());
    }
}
