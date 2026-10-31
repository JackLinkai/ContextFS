package com.contextfs.observability.log;

import java.time.Instant;
import java.util.List;

/**
 * 检索日志（retrieval_log 表）：一次检索请求的输入输出与耗时。
 *
 * @param traceId 链路追踪 ID
 * @param spanId 检索动作 span ID
 * @param sessionId 会话 ID，可空
 * @param agentId Agent ID，可空
 * @param scope 检索范围 scope
 * @param query 查询文本
 * @param mode 检索模式（search / find）
 * @param retrievedUris 命中节点 URI 列表（按分数降序）
 * @param levels 各命中节点实际加载的层级
 * @param latencyMs 服务端总耗时（毫秒）
 * @param promptTokens 返回内容合计 token 数
 * @param completionTokens 检索阶段无生成动作，恒为 0
 * @param status OK / ERROR
 * @param error 失败原因，成功时为 null
 * @param createdAt 记录时间
 */
public record RetrievalLogEntry(
        String traceId,
        String spanId,
        String sessionId,
        String agentId,
        String scope,
        String query,
        String mode,
        List<String> retrievedUris,
        List<String> levels,
        long latencyMs,
        int promptTokens,
        int completionTokens,
        String status,
        String error,
        Instant createdAt) {}
