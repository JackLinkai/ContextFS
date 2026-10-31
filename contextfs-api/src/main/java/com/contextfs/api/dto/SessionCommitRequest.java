package com.contextfs.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 会话提交请求体：POST /api/v1/sessions/{sessionId}/commit。
 *
 * @param agentId Agent ID，记忆目标 scope 为 {@code agent/{agentId}}（无 userId 时）
 * @param userId 用户 ID，记忆目标 scope 优先取 {@code user/{userId}}
 * @param messages 会话消息，归档为 {@code ctxfs://session/{sessionId}/archive.md}
 */
public record SessionCommitRequest(
        String agentId, String userId, @NotEmpty @Valid List<Message> messages) {

    /**
     * 会话消息。
     *
     * @param role 角色，如 {@code user} / {@code assistant}
     * @param content 消息内容
     */
    public record Message(@NotBlank String role, @NotBlank String content) {}
}
