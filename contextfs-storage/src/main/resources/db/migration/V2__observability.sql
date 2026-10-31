-- ContextFS 可观测 schema（Flyway 管理）
-- retrieval_log / tool_call_log / trace_span：按 traceId 回放完整链路

-- 检索日志：每次检索的输入输出、命中节点与 token 消耗
CREATE TABLE retrieval_log
(
    id                BIGSERIAL PRIMARY KEY,
    trace_id          TEXT        NOT NULL,
    span_id           TEXT,
    session_id        TEXT,
    agent_id          TEXT,
    scope             TEXT        NOT NULL,
    query             TEXT        NOT NULL,
    mode              TEXT        NOT NULL,
    retrieved_uris    JSONB       NOT NULL DEFAULT '[]'::jsonb,
    levels            JSONB       NOT NULL DEFAULT '[]'::jsonb,
    latency_ms        BIGINT      NOT NULL DEFAULT 0,
    prompt_tokens     INT         NOT NULL DEFAULT 0,
    completion_tokens INT         NOT NULL DEFAULT 0,
    status            TEXT        NOT NULL DEFAULT 'OK',
    error             TEXT,
    created_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_retrieval_log_trace ON retrieval_log (trace_id);

-- 工具调用日志：记忆提取 / 合并决策等动作的输入输出
CREATE TABLE tool_call_log
(
    id         BIGSERIAL PRIMARY KEY,
    trace_id   TEXT        NOT NULL,
    span_id    TEXT,
    session_id TEXT,
    agent_id   TEXT,
    tool       TEXT        NOT NULL,
    input      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    output     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    latency_ms BIGINT      NOT NULL DEFAULT 0,
    status     TEXT        NOT NULL DEFAULT 'OK',
    error      TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_tool_call_log_trace ON tool_call_log (trace_id);

-- 链路 span：REST 入口与关键动作 span，支持按 traceId 回放
CREATE TABLE trace_span
(
    id             BIGSERIAL PRIMARY KEY,
    trace_id       TEXT        NOT NULL,
    span_id        TEXT        NOT NULL,
    parent_span_id TEXT,
    operation      TEXT        NOT NULL,
    attributes     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    status         TEXT        NOT NULL DEFAULT 'OK',
    started_at     TIMESTAMPTZ NOT NULL,
    ended_at       TIMESTAMPTZ NOT NULL,
    latency_ms     BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_trace_span_trace ON trace_span (trace_id, started_at);
