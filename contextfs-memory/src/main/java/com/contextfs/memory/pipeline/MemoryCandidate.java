package com.contextfs.memory.pipeline;

/**
 * 提取出的候选记忆。
 *
 * @param type 记忆类型
 * @param content 记忆内容（取自会话原句）
 */
public record MemoryCandidate(MemoryType type, String content) {}
