package com.contextfs.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 链路回放详情（GET /api/v1/traces/{traceId} 响应体）：入口 span、检索日志与工具调用日志聚合。
 *
 * @param traceId 链路追踪 ID
 * @param spans 链路 span 列表（按开始时间）
 * @param retrievalLogs 检索日志列表（按写入顺序）
 * @param toolCallLogs 工具调用日志列表（按写入顺序）
 */
public record TraceDetailDto(
        String traceId,
        List<SpanDto> spans,
        List<RetrievalLogDto> retrievalLogs,
        List<ToolCallLogDto> toolCallLogs) {

    /**
     * 链路 span。
     *
     * @param spanId span ID
     * @param parentSpanId 父 span ID，入口 span 为 null
     * @param operation 动作名
     * @param attributes 附加属性
     * @param status OK / ERROR
     * @param startedAt 开始时间
     * @param endedAt 结束时间
     * @param latencyMs 耗时（毫秒）
     */
    public record SpanDto(
            String spanId,
            String parentSpanId,
            String operation,
            Map<String, String> attributes,
            String status,
            Instant startedAt,
            Instant endedAt,
            long latencyMs) {}

    /**
     * 检索日志。
     *
     * @param spanId 检索动作 span ID
     * @param scope 检索范围
     * @param query 查询文本
     * @param mode 检索模式
     * @param retrievedUris 命中节点 URI 列表
     * @param levels 命中节点加载层级
     * @param latencyMs 耗时（毫秒）
     * @param promptTokens 返回内容合计 token
     * @param completionTokens 生成 token（检索恒为 0）
     * @param status OK / ERROR
     * @param error 失败原因
     * @param createdAt 记录时间
     */
    public record RetrievalLogDto(
            String spanId,
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

    /**
     * 工具调用日志。
     *
     * @param spanId 工具调用 span ID
     * @param sessionId 会话 ID
     * @param agentId Agent ID
     * @param tool 工具名
     * @param input 输入（JSON 字符串）
     * @param output 输出（JSON 字符串）
     * @param latencyMs 耗时（毫秒）
     * @param status OK / ERROR
     * @param error 失败原因
     * @param createdAt 记录时间
     */
    public record ToolCallLogDto(
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
}
