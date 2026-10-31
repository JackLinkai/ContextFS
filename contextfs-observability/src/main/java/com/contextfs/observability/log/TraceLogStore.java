package com.contextfs.observability.log;

import java.util.List;

/**
 * 链路日志存储端口：写入并按 {@code traceId} 查询三类日志，支撑 bad case 回放。
 *
 * <p>缺省实现为 {@link InMemoryTraceLogStore}（测试与单机模式）；生产装配见 storage 模块的 PgTraceLogStore（PostgreSQL
 * retrieval_log / tool_call_log / trace_span 三表）。
 */
public interface TraceLogStore {

    /** 追加一条检索日志。 */
    void appendRetrievalLog(RetrievalLogEntry entry);

    /** 追加一条工具调用日志。 */
    void appendToolCallLog(ToolCallLogEntry entry);

    /** 追加一条链路 span。 */
    void appendSpan(TraceSpanEntry span);

    /** 按 traceId 查询检索日志，按写入顺序返回。 */
    List<RetrievalLogEntry> retrievalLogs(String traceId);

    /** 按 traceId 查询工具调用日志，按写入顺序返回。 */
    List<ToolCallLogEntry> toolCallLogs(String traceId);

    /** 按 traceId 查询链路 span，按开始时间返回。 */
    List<TraceSpanEntry> spans(String traceId);
}
