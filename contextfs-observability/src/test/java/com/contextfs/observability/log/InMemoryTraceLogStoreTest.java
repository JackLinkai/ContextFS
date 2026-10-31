package com.contextfs.observability.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link InMemoryTraceLogStore} 单元测试：写入与按 traceId 查询隔离。 */
@DisplayName("InMemoryTraceLogStore 链路日志存储")
class InMemoryTraceLogStoreTest {

    private static final String TRACE_A = "trace-a";
    private static final String TRACE_B = "trace-b";

    private InMemoryTraceLogStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryTraceLogStore();
    }

    @Test
    @DisplayName("追加与查询：按 traceId 过滤且保持写入顺序")
    void appendAndQueryByTraceId() {
        store.appendRetrievalLog(retrievalLog(TRACE_A, "q1"));
        store.appendRetrievalLog(retrievalLog(TRACE_B, "q2"));
        store.appendRetrievalLog(retrievalLog(TRACE_A, "q3"));

        List<RetrievalLogEntry> logs = store.retrievalLogs(TRACE_A);
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).query()).isEqualTo("q1");
        assertThat(logs.get(1).query()).isEqualTo("q3");
        assertThat(logs.get(0).retrievedUris()).isUnmodifiable();

        assertThat(store.retrievalLogs(TRACE_B)).hasSize(1);
        assertThat(store.retrievalLogs("missing")).isEmpty();
        assertThat(store.retrievalLogs(null)).isEmpty();
    }

    @Test
    @DisplayName("工具调用日志按 traceId 查询")
    void toolCallLogs() {
        store.appendToolCallLog(
                new ToolCallLogEntry(
                        TRACE_A,
                        "span-1",
                        "sess-1",
                        "coder",
                        "memory.extract",
                        "{\"messageCount\":2}",
                        "{\"candidates\":1}",
                        3,
                        "OK",
                        null,
                        Instant.now()));

        assertThat(store.toolCallLogs(TRACE_A)).hasSize(1);
        assertThat(store.toolCallLogs(TRACE_A).get(0).tool()).isEqualTo("memory.extract");
        assertThat(store.toolCallLogs(TRACE_B)).isEmpty();
    }

    @Test
    @DisplayName("span 按 traceId 查询且属性不可变")
    void spans() {
        store.appendSpan(
                new TraceSpanEntry(
                        TRACE_A,
                        "span-1",
                        null,
                        "HTTP POST /api/v1/retrieval/search",
                        Map.of("method", "POST", "http.status", "200"),
                        "OK",
                        Instant.now(),
                        Instant.now(),
                        5));
        store.appendSpan(
                new TraceSpanEntry(
                        TRACE_B,
                        "span-2",
                        null,
                        "HTTP GET /x",
                        Map.of(),
                        "OK",
                        Instant.now(),
                        Instant.now(),
                        1));

        List<TraceSpanEntry> spans = store.spans(TRACE_A);
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).operation()).contains("/api/v1/retrieval/search");
        assertThat(spans.get(0).attributes()).isUnmodifiable();
        assertThat(store.spans(TRACE_B)).hasSize(1);
    }

    private RetrievalLogEntry retrievalLog(String traceId, String query) {
        return new RetrievalLogEntry(
                traceId,
                "span-" + query,
                null,
                null,
                "global",
                query,
                "search",
                List.of("ctxfs://global/docs/a.md"),
                List.of("L0"),
                10,
                20,
                0,
                "OK",
                null,
                Instant.now());
    }
}
