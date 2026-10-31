package com.contextfs.memory.pipeline;

/**
 * 单条记忆的处理结果。
 *
 * @param uri 记忆节点 URI（新建 / 合并时为目标节点，跳过时为最相似已有节点）
 * @param type 记忆类型
 * @param decision 决策结果
 * @param similarity 与已有记忆的最大相似度（0-1，无已有记忆时为 0）
 */
public record MemoryChange(
        String uri, MemoryType type, MergeDecision decision, double similarity) {}
