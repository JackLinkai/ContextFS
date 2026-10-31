package com.contextfs.observability.log;

import java.time.Instant;
import java.util.Map;

/**
 * 链路 span（trace_span 表）：按 traceId 回放链路的基本单元。
 *
 * @param traceId 链路追踪 ID
 * @param spanId span ID
 * @param parentSpanId 父 span ID，入口 span 为 null
 * @param operation 动作名，如 HTTP POST /api/v1/retrieval/search
 * @param attributes 附加属性（method、http.path、http.status 等）
 * @param status OK / ERROR
 * @param startedAt 开始时间
 * @param endedAt 结束时间
 * @param latencyMs 执行耗时（毫秒）
 */
public record TraceSpanEntry(
        String traceId,
        String spanId,
        String parentSpanId,
        String operation,
        Map<String, String> attributes,
        String status,
        Instant startedAt,
        Instant endedAt,
        long latencyMs) {}
