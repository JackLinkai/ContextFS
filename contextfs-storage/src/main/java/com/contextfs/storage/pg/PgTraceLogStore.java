package com.contextfs.storage.pg;

import com.contextfs.observability.log.RetrievalLogEntry;
import com.contextfs.observability.log.ToolCallLogEntry;
import com.contextfs.observability.log.TraceLogStore;
import com.contextfs.observability.log.TraceSpanEntry;
import com.contextfs.storage.StorageException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link TraceLogStore} 的 PostgreSQL 适配实现：retrieval_log / tool_call_log / trace_span 三表， 按 traceId
 * 回放链路。
 */
public class PgTraceLogStore implements TraceLogStore {

    private static final String INSERT_RETRIEVAL_SQL =
            """
            INSERT INTO retrieval_log (trace_id, span_id, session_id, agent_id, scope, query, mode,
                                       retrieved_uris, levels, latency_ms, prompt_tokens,
                                       completion_tokens, status, error, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_RETRIEVAL_SQL =
            """
            SELECT trace_id, span_id, session_id, agent_id, scope, query, mode,
                   retrieved_uris::text, levels::text, latency_ms, prompt_tokens,
                   completion_tokens, status, error, created_at
            FROM retrieval_log WHERE trace_id = ? ORDER BY id
            """;

    private static final String INSERT_TOOL_CALL_SQL =
            """
            INSERT INTO tool_call_log (trace_id, span_id, session_id, agent_id, tool, input, output,
                                       latency_ms, status, error, created_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
            """;

    private static final String SELECT_TOOL_CALL_SQL =
            """
            SELECT trace_id, span_id, session_id, agent_id, tool, input::text, output::text,
                   latency_ms, status, error, created_at
            FROM tool_call_log WHERE trace_id = ? ORDER BY id
            """;

    private static final String INSERT_SPAN_SQL =
            """
            INSERT INTO trace_span (trace_id, span_id, parent_span_id, operation, attributes,
                                    status, started_at, ended_at, latency_ms)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            """;

    private static final String SELECT_SPAN_SQL =
            """
            SELECT trace_id, span_id, parent_span_id, operation, attributes::text, status,
                   started_at, ended_at, latency_ms
            FROM trace_span WHERE trace_id = ? ORDER BY started_at, id
            """;

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE =
            new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PgTraceLogStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void appendRetrievalLog(RetrievalLogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        jdbcTemplate.update(
                INSERT_RETRIEVAL_SQL,
                entry.traceId(),
                entry.spanId(),
                entry.sessionId(),
                entry.agentId(),
                entry.scope(),
                entry.query(),
                entry.mode(),
                toJson(entry.retrievedUris()),
                toJson(entry.levels()),
                entry.latencyMs(),
                entry.promptTokens(),
                entry.completionTokens(),
                entry.status(),
                entry.error(),
                Timestamp.from(entry.createdAt()));
    }

    @Override
    public List<RetrievalLogEntry> retrievalLogs(String traceId) {
        return jdbcTemplate.query(
                SELECT_RETRIEVAL_SQL,
                (rs, rowNum) ->
                        new RetrievalLogEntry(
                                rs.getString("trace_id"),
                                rs.getString("span_id"),
                                rs.getString("session_id"),
                                rs.getString("agent_id"),
                                rs.getString("scope"),
                                rs.getString("query"),
                                rs.getString("mode"),
                                fromJsonList(rs.getString("retrieved_uris")),
                                fromJsonList(rs.getString("levels")),
                                rs.getLong("latency_ms"),
                                rs.getInt("prompt_tokens"),
                                rs.getInt("completion_tokens"),
                                rs.getString("status"),
                                rs.getString("error"),
                                toInstant(rs.getTimestamp("created_at"))),
                traceId);
    }

    @Override
    public void appendToolCallLog(ToolCallLogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        jdbcTemplate.update(
                INSERT_TOOL_CALL_SQL,
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
                Timestamp.from(entry.createdAt()));
    }

    @Override
    public List<ToolCallLogEntry> toolCallLogs(String traceId) {
        return jdbcTemplate.query(
                SELECT_TOOL_CALL_SQL,
                (rs, rowNum) ->
                        new ToolCallLogEntry(
                                rs.getString("trace_id"),
                                rs.getString("span_id"),
                                rs.getString("session_id"),
                                rs.getString("agent_id"),
                                rs.getString("tool"),
                                rs.getString("input"),
                                rs.getString("output"),
                                rs.getLong("latency_ms"),
                                rs.getString("status"),
                                rs.getString("error"),
                                toInstant(rs.getTimestamp("created_at"))),
                traceId);
    }

    @Override
    public void appendSpan(TraceSpanEntry span) {
        Objects.requireNonNull(span, "span");
        jdbcTemplate.update(
                INSERT_SPAN_SQL,
                span.traceId(),
                span.spanId(),
                span.parentSpanId(),
                span.operation(),
                toJson(span.attributes()),
                span.status(),
                Timestamp.from(span.startedAt()),
                Timestamp.from(span.endedAt()),
                span.latencyMs());
    }

    @Override
    public List<TraceSpanEntry> spans(String traceId) {
        return jdbcTemplate.query(
                SELECT_SPAN_SQL,
                (rs, rowNum) ->
                        new TraceSpanEntry(
                                rs.getString("trace_id"),
                                rs.getString("span_id"),
                                rs.getString("parent_span_id"),
                                rs.getString("operation"),
                                fromJsonMap(rs.getString("attributes")),
                                rs.getString("status"),
                                toInstant(rs.getTimestamp("started_at")),
                                toInstant(rs.getTimestamp("ended_at")),
                                rs.getLong("latency_ms")),
                traceId);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new StorageException("Trace log JSON serialization failed", e);
        }
    }

    private List<String> fromJsonList(String json) {
        try {
            return json == null ? List.of() : objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new StorageException("Trace log JSON deserialization failed", e);
        }
    }

    private Map<String, String> fromJsonMap(String json) {
        try {
            return json == null ? Map.of() : objectMapper.readValue(json, STRING_MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new StorageException("Trace log JSON deserialization failed", e);
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
