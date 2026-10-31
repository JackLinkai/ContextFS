package com.contextfs.observability.log;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link TraceLogStore} 的内存实现：CopyOnWriteArrayList 存储，写入顺序即返回顺序。 用于测试与无 PostgreSQL 的单机模式；应用重启数据即丢失。
 */
public class InMemoryTraceLogStore implements TraceLogStore {

    private final List<RetrievalLogEntry> retrievalLogs = new CopyOnWriteArrayList<>();
    private final List<ToolCallLogEntry> toolCallLogs = new CopyOnWriteArrayList<>();
    private final List<TraceSpanEntry> spans = new CopyOnWriteArrayList<>();

    @Override
    public void appendRetrievalLog(RetrievalLogEntry entry) {
        retrievalLogs.add(Objects.requireNonNull(entry, "entry"));
    }

    @Override
    public void appendToolCallLog(ToolCallLogEntry entry) {
        toolCallLogs.add(Objects.requireNonNull(entry, "entry"));
    }

    @Override
    public void appendSpan(TraceSpanEntry span) {
        spans.add(Objects.requireNonNull(span, "span"));
    }

    @Override
    public List<RetrievalLogEntry> retrievalLogs(String traceId) {
        return retrievalLogs.stream()
                .filter(entry -> matches(entry.traceId(), traceId))
                .map(
                        entry ->
                                new RetrievalLogEntry(
                                        entry.traceId(),
                                        entry.spanId(),
                                        entry.sessionId(),
                                        entry.agentId(),
                                        entry.scope(),
                                        entry.query(),
                                        entry.mode(),
                                        List.copyOf(entry.retrievedUris()),
                                        List.copyOf(entry.levels()),
                                        entry.latencyMs(),
                                        entry.promptTokens(),
                                        entry.completionTokens(),
                                        entry.status(),
                                        entry.error(),
                                        entry.createdAt()))
                .toList();
    }

    @Override
    public List<ToolCallLogEntry> toolCallLogs(String traceId) {
        return toolCallLogs.stream()
                .filter(entry -> matches(entry.traceId(), traceId))
                .map(
                        entry ->
                                new ToolCallLogEntry(
                                        entry.traceId(),
                                        entry.spanId(),
                                        entry.sessionId(),
                                        entry.agentId(),
                                        entry.tool(),
                                        entry.input(),
                                        entry.output(),
                                        entry.latencyMs(),
                                        entry.status(),
                                        entry.error(),
                                        entry.createdAt()))
                .toList();
    }

    @Override
    public List<TraceSpanEntry> spans(String traceId) {
        return spans.stream()
                .filter(span -> matches(span.traceId(), traceId))
                .map(
                        span ->
                                new TraceSpanEntry(
                                        span.traceId(),
                                        span.spanId(),
                                        span.parentSpanId(),
                                        span.operation(),
                                        Map.copyOf(span.attributes()),
                                        span.status(),
                                        span.startedAt(),
                                        span.endedAt(),
                                        span.latencyMs()))
                .toList();
    }

    private static boolean matches(String actual, String traceId) {
        return actual != null && actual.equals(traceId);
    }
}
