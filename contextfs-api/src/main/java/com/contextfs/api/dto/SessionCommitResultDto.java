package com.contextfs.api.dto;

import com.contextfs.memory.pipeline.MemoryType;
import com.contextfs.memory.pipeline.MergeDecision;
import java.util.List;

/**
 * 会话提交结果。
 *
 * @param sessionId 会话 ID
 * @param archivedUri 归档节点 URI
 * @param status {@code COMPLETED}（同步完成）/ {@code ACCEPTED}（异步提取中）
 * @param changes 记忆变更明细（异步模式为空）
 */
public record SessionCommitResultDto(
        String sessionId, String archivedUri, String status, List<MemoryChangeDto> changes) {

    /**
     * 单条记忆变更。
     *
     * @param uri 记忆节点 URI
     * @param type 记忆类型
     * @param decision 新建 / 合并 / 跳过
     * @param similarity 与已有记忆的最大相似度
     */
    public record MemoryChangeDto(
            String uri, MemoryType type, MergeDecision decision, double similarity) {}
}
