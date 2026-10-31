package com.contextfs.memory.pipeline;

/** 会话消息：归档与记忆提取的输入。 */
public record SessionMessage(String role, String content) {}
