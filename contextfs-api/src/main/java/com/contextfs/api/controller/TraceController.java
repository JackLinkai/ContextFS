package com.contextfs.api.controller;

import com.contextfs.api.common.ApiResponse;
import com.contextfs.api.dto.TraceDetailDto;
import com.contextfs.core.exception.TraceNotFoundException;
import com.contextfs.observability.log.RetrievalLogEntry;
import com.contextfs.observability.log.ToolCallLogEntry;
import com.contextfs.observability.log.TraceLogStore;
import com.contextfs.observability.log.TraceSpanEntry;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 链路回放 REST 接口：按 traceId 聚合 span、检索日志与工具调用日志，支持 bad case 回放。 */
@RestController
public class TraceController {

    private final TraceLogStore traceLogStore;

    public TraceController(TraceLogStore traceLogStore) {
        this.traceLogStore = traceLogStore;
    }

    /** 按 traceId 回放链路；三类日志均无记录时返回 404。 */
    @GetMapping("/api/v1/traces/{traceId}")
    public ApiResponse<TraceDetailDto> trace(@PathVariable String traceId) {
        List<TraceSpanEntry> spans = traceLogStore.spans(traceId);
        List<RetrievalLogEntry> retrievalLogs = traceLogStore.retrievalLogs(traceId);
        List<ToolCallLogEntry> toolCallLogs = traceLogStore.toolCallLogs(traceId);
        if (spans.isEmpty() && retrievalLogs.isEmpty() && toolCallLogs.isEmpty()) {
            throw new TraceNotFoundException(traceId);
        }
        return ApiResponse.ok(toDto(traceId, spans, retrievalLogs, toolCallLogs));
    }

    private TraceDetailDto toDto(
            String traceId,
            List<TraceSpanEntry> spans,
            List<RetrievalLogEntry> retrievalLogs,
            List<ToolCallLogEntry> toolCallLogs) {
        List<TraceDetailDto.SpanDto> spanDtos =
                spans.stream()
                        .map(
                                span ->
                                        new TraceDetailDto.SpanDto(
                                                span.spanId(),
                                                span.parentSpanId(),
                                                span.operation(),
                                                span.attributes(),
                                                span.status(),
                                                span.startedAt(),
                                                span.endedAt(),
                                                span.latencyMs()))
                        .toList();
        List<TraceDetailDto.RetrievalLogDto> retrievalDtos =
                retrievalLogs.stream()
                        .map(
                                entry ->
                                        new TraceDetailDto.RetrievalLogDto(
                                                entry.spanId(),
                                                entry.scope(),
                                                entry.query(),
                                                entry.mode(),
                                                entry.retrievedUris(),
                                                entry.levels(),
                                                entry.latencyMs(),
                                                entry.promptTokens(),
                                                entry.completionTokens(),
                                                entry.status(),
                                                entry.error(),
                                                entry.createdAt()))
                        .toList();
        List<TraceDetailDto.ToolCallLogDto> toolCallDtos =
                toolCallLogs.stream()
                        .map(
                                entry ->
                                        new TraceDetailDto.ToolCallLogDto(
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
        return new TraceDetailDto(traceId, spanDtos, retrievalDtos, toolCallDtos);
    }
}
