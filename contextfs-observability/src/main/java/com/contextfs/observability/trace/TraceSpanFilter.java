package com.contextfs.observability.trace;

import com.contextfs.observability.log.TraceLogStore;
import com.contextfs.observability.log.TraceSpanEntry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * REST 入口链路过滤器：解析或生成 traceId 贯穿本次请求，注入 MDC（日志自动携带 traceId/spanId）， 请求结束时向 {@link TraceLogStore}
 * 写入入口 span，供按 traceId 回放完整链路。
 *
 * <p>traceId 来源：{@code X-ContextFS-Trace-Id} 请求头优先，缺省生成 UUID；响应头原样返回。 已排除 actuator 端点，避免健康检查刷日志。
 */
public class TraceSpanFilter extends OncePerRequestFilter {

    /** 链路追踪请求/响应头。 */
    public static final String TRACE_HEADER = "X-ContextFS-Trace-Id";

    /** 请求属性：业务 traceId 在请求内的传递键。 */
    public static final String TRACE_ATTRIBUTE = "ctxfs.traceId";

    /** MDC 中 traceId 的键，logback pattern 引用。 */
    public static final String MDC_TRACE_ID = "traceId";

    /** MDC 中 spanId 的键，logback pattern 引用。 */
    public static final String MDC_SPAN_ID = "spanId";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TraceLogStore traceLogStore;

    public TraceSpanFilter(TraceLogStore traceLogStore) {
        this.traceLogStore = traceLogStore;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = currentTraceId(request);
        String spanId = newSpanId();
        Instant startedAt = Instant.now();
        long start = System.nanoTime();
        MDC.put(MDC_TRACE_ID, traceId);
        MDC.put(MDC_SPAN_ID, spanId);
        request.setAttribute(TRACE_ATTRIBUTE, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            Instant endedAt = Instant.now();
            int httpStatus = response.getStatus();
            appendSpanQuietly(request, traceId, spanId, startedAt, endedAt, httpStatus, start);
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_SPAN_ID);
        }
    }

    /** traceId：请求头优先，缺省生成 UUID。 */
    private String currentTraceId(HttpServletRequest request) {
        String header = request.getHeader(TRACE_HEADER);
        return header == null || header.isBlank() ? UUID.randomUUID().toString() : header.trim();
    }

    private void appendSpanQuietly(
            HttpServletRequest request,
            String traceId,
            String spanId,
            Instant startedAt,
            Instant endedAt,
            int httpStatus,
            long startNanos) {
        try {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            traceLogStore.appendSpan(
                    new TraceSpanEntry(
                            traceId,
                            spanId,
                            null,
                            "HTTP " + request.getMethod() + " " + request.getRequestURI(),
                            Map.of(
                                    "method",
                                    request.getMethod(),
                                    "http.path",
                                    request.getRequestURI(),
                                    "http.status",
                                    String.valueOf(httpStatus)),
                            httpStatus < 400 ? "OK" : "ERROR",
                            startedAt,
                            endedAt,
                            latencyMs));
        } catch (RuntimeException e) {
            // 日志记录失败不影响请求主流程
        }
    }

    /** 生成 16 位十六进制 spanId。 */
    public static String newSpanId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** span 耗时（毫秒）工具：endedAt - startedAt。 */
    public static long latencyMs(Instant startedAt, Instant endedAt) {
        return Duration.between(startedAt, endedAt).toMillis();
    }
}
