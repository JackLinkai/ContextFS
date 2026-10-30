-- ContextFS 初始 schema（Flyway 管理）
-- 节点树 / 三层内容 / 会话记录

CREATE TABLE ctx_node
(
    uri        TEXT PRIMARY KEY,
    scope      TEXT        NOT NULL,
    path       TEXT        NOT NULL,
    node_type  TEXT        NOT NULL CHECK (node_type IN ('DIRECTORY', 'DOCUMENT', 'MEMORY', 'SKILL')),
    parent_uri TEXT,
    metadata   JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_ctx_node_scope_path UNIQUE (scope, path)
);

-- ls / tree 查询按父节点定位
CREATE INDEX idx_ctx_node_parent ON ctx_node (parent_uri);

-- 三层内容：随节点级联删除；节点本身删除不级联子节点
CREATE TABLE ctx_level
(
    uri         TEXT        NOT NULL REFERENCES ctx_node (uri) ON DELETE CASCADE,
    level       TEXT        NOT NULL CHECK (level IN ('L0', 'L1', 'L2')),
    content     TEXT        NOT NULL DEFAULT '',
    token_count INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (uri, level)
);

-- 检索阶段按层级扫描目录摘要
CREATE INDEX idx_ctx_level_level ON ctx_level (level);

-- 会话记录（阶段 6 记忆管线使用，此处预留结构）
CREATE TABLE session_record
(
    id         BIGSERIAL PRIMARY KEY,
    scope      TEXT        NOT NULL,
    title      TEXT        NOT NULL DEFAULT '',
    messages   JSONB       NOT NULL DEFAULT '[]'::jsonb,
    status     TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_session_record_scope ON session_record (scope, status);
