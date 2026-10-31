package com.contextfs.observability.log;

import java.time.Instant;

/**
 * 工具调用日志（tool_call_log 表）：一次工具动作（如记忆提取、合并决策）的输入输出与耗时。
 *
 * @param traceId 链路追踪 ID
 * @param spanId 工具调用 span ID
 * @param sessionId 会话 ID，可空
 * @param agentId Agent ID，可空
 * @param tool 工具名，如 memory.extract / memory.decide
 * @param input 输入参数（JSON 字符串）
 * @param output 输出结果（JSON 字符串）
 * @param latencyMs 执行耗时（毫秒）
 * @param status OK / ERROR
 * @param error 失败原因，成功时为 null
 * @param createdAt 记录时间
 */
public record ToolCallLogEntry(
        String traceId,
        String spanId,
        String sessionId,
        String agentId,
        String tool,
        String input,
        String output,
        long latencyMs,
        String status,
        String error,
        Instant createdAt) {}
