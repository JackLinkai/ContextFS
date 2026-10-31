package com.contextfs.api.dto;

import com.contextfs.core.model.NodeType;
import java.time.Instant;

/** 写入节点的响应。 */
public record WriteResultDto(String uri, NodeType type, Instant updatedAt) {}
