package com.contextfs.api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contextfs.api.common.GlobalExceptionHandler;
import com.contextfs.observability.log.InMemoryTraceLogStore;
import com.contextfs.observability.log.RetrievalLogEntry;
import com.contextfs.observability.log.TraceLogStore;
import com.contextfs.observability.log.TraceSpanEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** TraceController 链路回放接口契约测试：InMemory 装配，不依赖外部服务。 */
@DisplayName("TraceController 链路回放接口")
class TraceControllerTest {

    private static final String TRACE_ID = "trace-replay-001";

    private final TraceLogStore store = new InMemoryTraceLogStore();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(new TraceController(store))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        seed();
    }

    private void seed() {
        store.appendSpan(
                new TraceSpanEntry(
                        TRACE_ID,
                        "span-entry",
                        null,
                        "HTTP POST /api/v1/retrieval/search",
                        Map.of(
                                "method",
                                "POST",
                                "http.path",
                                "/api/v1/retrieval/search",
                                "http.status",
                                "200"),
                        "OK",
                        Instant.now(),
                        Instant.now(),
                        12));
        store.appendRetrievalLog(
                new RetrievalLogEntry(
                        TRACE_ID,
                        "span-retrieval",
                        null,
                        null,
                        "global",
                        "网络搜索 技能",
                        "search",
                        List.of("ctxfs://global/skills/web_search.md"),
                        List.of("L1"),
                        8,
                        120,
                        0,
                        "OK",
                        null,
                        Instant.now()));
        store.appendToolCallLog(
                new com.contextfs.observability.log.ToolCallLogEntry(
                        TRACE_ID,
                        "span-tool",
                        "sess-1",
                        "coder",
                        "memory.extract",
                        "{\"messageCount\":2}",
                        "{\"candidates\":1}",
                        3,
                        "OK",
                        null,
                        Instant.now()));
    }

    @Test
    @DisplayName("GET /traces/{traceId} 聚合返回 span、检索日志与工具调用日志")
    void replaysFullTrace() throws Exception {
        mockMvc.perform(get("/api/v1/traces/{traceId}", TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.traceId").value(TRACE_ID))
                .andExpect(
                        jsonPath("$.data.spans[0].operation")
                                .value("HTTP POST /api/v1/retrieval/search"))
                .andExpect(jsonPath("$.data.spans[0].attributes['http.status']").value("200"))
                .andExpect(jsonPath("$.data.retrievalLogs[0].query").value("网络搜索 技能"))
                .andExpect(jsonPath("$.data.retrievalLogs[0].mode").value("search"))
                .andExpect(
                        jsonPath("$.data.retrievalLogs[0].retrievedUris[0]")
                                .value("ctxfs://global/skills/web_search.md"))
                .andExpect(jsonPath("$.data.retrievalLogs[0].levels[0]").value("L1"))
                .andExpect(jsonPath("$.data.retrievalLogs[0].promptTokens").value(120))
                .andExpect(jsonPath("$.data.toolCallLogs[0].tool").value("memory.extract"))
                .andExpect(jsonPath("$.data.toolCallLogs[0].status").value("OK"));
    }

    @Test
    @DisplayName("未知 traceId 返回 404")
    void returns404WhenTraceMissing() throws Exception {
        mockMvc.perform(get("/api/v1/traces/{traceId}", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("响应为统一 envelope 且 content-type 为 JSON")
    void responseEnvelope() throws Exception {
        mockMvc.perform(
                        get("/api/v1/traces/{traceId}", TRACE_ID)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("ok"));
    }
}
