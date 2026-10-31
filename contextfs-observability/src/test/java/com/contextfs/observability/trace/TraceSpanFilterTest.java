package com.contextfs.observability.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contextfs.observability.log.InMemoryTraceLogStore;
import com.contextfs.observability.log.TraceLogStore;
import com.contextfs.observability.log.TraceSpanEntry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@link TraceSpanFilter} 单元测试：traceId 贯穿、MDC 注入与入口 span 记录。 */
@DisplayName("TraceSpanFilter REST 入口链路过滤器")
class TraceSpanFilterTest {

    private final TraceLogStore store = new InMemoryTraceLogStore();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(new EchoController())
                        .addFilters(new TraceSpanFilter(store))
                        .build();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("请求头携带 traceId 时透传到响应头并记录入口 span")
    void propagatesHeaderTraceId() throws Exception {
        mockMvc.perform(
                        get("/echo")
                                .header(TraceSpanFilter.TRACE_HEADER, "trace-e2e-001")
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceSpanFilter.TRACE_HEADER, "trace-e2e-001"));

        List<TraceSpanEntry> spans = store.spans("trace-e2e-001");
        assertThat(spans).hasSize(1);
        TraceSpanEntry span = spans.get(0);
        assertThat(span.spanId()).hasSize(16);
        assertThat(span.parentSpanId()).isNull();
        assertThat(span.operation()).isEqualTo("HTTP GET /echo");
        assertThat(span.status()).isEqualTo("OK");
        assertThat(span.attributes()).containsEntry("http.path", "/echo");
        assertThat(span.attributes()).containsEntry("http.status", "200");
        assertThat(span.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("请求头缺省时生成 UUID traceId 并回写响应头")
    void generatesTraceIdWhenHeaderMissing() throws Exception {
        String traceId =
                mockMvc.perform(get("/echo"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getHeader(TraceSpanFilter.TRACE_HEADER);
        assertThat(traceId).isNotBlank();
        assertThat(store.spans(traceId)).hasSize(1);
    }

    @Test
    @DisplayName("请求结束后清理 MDC 中的 traceId/spanId")
    void clearsMdcAfterRequest() throws Exception {
        mockMvc.perform(get("/echo").header(TraceSpanFilter.TRACE_HEADER, "t-mdc"));
        assertThat(MDC.get(TraceSpanFilter.MDC_TRACE_ID)).isNull();
        assertThat(MDC.get(TraceSpanFilter.MDC_SPAN_ID)).isNull();
    }

    @Test
    @DisplayName("actuator 端点跳过过滤，不产生入口 span")
    void skipsActuatorEndpoints() {
        TraceSpanFilter filter = new TraceSpanFilter(store);
        MockHttpServletRequest actuator = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletRequest business = new MockHttpServletRequest("GET", "/echo");
        assertThat(filter.shouldNotFilter(actuator)).isTrue();
        assertThat(filter.shouldNotFilter(business)).isFalse();
        assertThat(store.spans("")).isEmpty();
    }

    @RestController
    static class EchoController {

        @GetMapping("/echo")
        public String echo() {
            // 模拟业务代码在请求内从 MDC 读取链路上下文
            assertThat(MDC.get(TraceSpanFilter.MDC_TRACE_ID)).isNotNull();
            assertThat(MDC.get(TraceSpanFilter.MDC_SPAN_ID)).isNotNull();
            return "echo";
        }
    }
}
