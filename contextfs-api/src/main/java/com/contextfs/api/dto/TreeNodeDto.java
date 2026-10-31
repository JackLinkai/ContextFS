package com.contextfs.api.dto;

import com.contextfs.core.model.ContextNode;
import com.contextfs.core.model.NodeType;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 目录树节点 DTO，children 顺序与仓储返回顺序一致（按路径排序）。 */
public record TreeNodeDto(
        String uri,
        String name,
        NodeType type,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt,
        List<TreeNodeDto> children) {

    public static TreeNodeDto from(ContextNode node, List<TreeNodeDto> children) {
        return new TreeNodeDto(
                node.uri().toString(),
                node.uri().name(),
                node.type(),
                node.metadata(),
                node.createdAt(),
                node.updatedAt(),
                children);
    }
}
