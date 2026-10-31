package com.contextfs.memory.pipeline;

import java.util.List;

/**
 * 一次会话提交的处理结果。
 *
 * @param archivedUri 归档节点 URI（ctxfs://session/{id}/archive.md）
 * @param changes 提取与合并决策明细（enabled=false 时为空）
 */
public record CommitOutcome(String archivedUri, List<MemoryChange> changes) {}
