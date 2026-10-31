package com.contextfs.api.dto;

import com.contextfs.core.model.NodeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * 写入节点请求。
 *
 * @param generateLevels 预留字段：是否自动生成 L0/L1 摘要，阶段 4 接入 LLM 后生效
 */
public record WriteRequest(
        @NotBlank String uri,
        @NotNull String content,
        NodeType type,
        Map<String, String> metadata,
        Boolean generateLevels) {}
