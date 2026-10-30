package com.contextfs.storage.pg;

import com.contextfs.core.model.ContextNode;
import com.contextfs.core.model.Level;
import com.contextfs.core.model.NodeLevel;
import com.contextfs.core.model.NodeType;
import com.contextfs.core.port.NodeRepository;
import com.contextfs.core.uri.CtxfsUri;
import com.contextfs.storage.StorageException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link NodeRepository} 的 PostgreSQL 适配实现，子树查询使用递归 CTE。 */
public class PgNodeRepository implements NodeRepository {

    private static final String NODE_COLUMNS =
            "uri, scope, path, node_type, parent_uri, metadata::text AS metadata, created_at, updated_at";

    private static final String UPSERT_NODE_SQL =
            """
            INSERT INTO ctx_node (uri, scope, path, node_type, parent_uri, metadata, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (uri) DO UPDATE
            SET node_type = EXCLUDED.node_type,
                parent_uri = EXCLUDED.parent_uri,
                metadata = EXCLUDED.metadata,
                updated_at = EXCLUDED.updated_at
            """;

    private static final String SELECT_BY_URI_SQL =
            "SELECT " + NODE_COLUMNS + " FROM ctx_node WHERE uri = ?";

    private static final String SELECT_CHILDREN_SQL =
            "SELECT " + NODE_COLUMNS + " FROM ctx_node WHERE parent_uri = ? ORDER BY path";

    /** maxDepth=0 仅返回 root；递归成员中 depth < maxDepth 才继续向下展开。 */
    private static final String SUBTREE_SQL =
            """
            WITH RECURSIVE subtree AS (
                SELECT uri, scope, path, node_type, parent_uri, metadata::text AS metadata,
                       created_at, updated_at, 0 AS depth
                FROM ctx_node
                WHERE uri = ?
                UNION ALL
                SELECT n.uri, n.scope, n.path, n.node_type, n.parent_uri, n.metadata::text AS metadata,
                       n.created_at, n.updated_at, s.depth + 1
                FROM ctx_node n
                JOIN subtree s ON n.parent_uri = s.uri
                WHERE s.depth < ?
            )
            SELECT uri, scope, path, node_type, parent_uri, metadata, created_at, updated_at
            FROM subtree
            ORDER BY path
            """;

    private static final String DELETE_NODE_SQL = "DELETE FROM ctx_node WHERE uri = ?";

    private static final String UPSERT_LEVEL_SQL =
            """
            INSERT INTO ctx_level (uri, level, content, token_count)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (uri, level) DO UPDATE
            SET content = EXCLUDED.content,
                token_count = EXCLUDED.token_count
            """;

    private static final String SELECT_LEVEL_SQL =
            "SELECT level, content, token_count FROM ctx_level WHERE uri = ? AND level = ?";

    private static final TypeReference<Map<String, String>> METADATA_TYPE =
            new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PgNodeRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void save(ContextNode node) {
        Objects.requireNonNull(node, "node");
        jdbcTemplate.update(
                UPSERT_NODE_SQL,
                node.uri().toString(),
                node.uri().scope(),
                node.uri().path(),
                node.type().name(),
                node.parent() == null ? null : node.parent().toString(),
                toJson(node.metadata()),
                Timestamp.from(node.createdAt()),
                Timestamp.from(node.updatedAt()));
    }

    @Override
    public Optional<ContextNode> findByUri(CtxfsUri uri) {
        Objects.requireNonNull(uri, "uri");
        List<ContextNode> nodes =
                jdbcTemplate.query(SELECT_BY_URI_SQL, this::mapNode, uri.toString());
        return nodes.stream().findFirst();
    }

    @Override
    public boolean exists(CtxfsUri uri) {
        Objects.requireNonNull(uri, "uri");
        List<String> uris =
                jdbcTemplate.queryForList(
                        "SELECT uri FROM ctx_node WHERE uri = ?", String.class, uri.toString());
        return !uris.isEmpty();
    }

    @Override
    public List<ContextNode> listChildren(CtxfsUri parent) {
        Objects.requireNonNull(parent, "parent");
        return jdbcTemplate.query(SELECT_CHILDREN_SQL, this::mapNode, parent.toString());
    }

    @Override
    public List<ContextNode> listSubtree(CtxfsUri root, int maxDepth) {
        Objects.requireNonNull(root, "root");
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must be >= 0: " + maxDepth);
        }
        return jdbcTemplate.query(SUBTREE_SQL, this::mapNode, root.toString(), maxDepth);
    }

    @Override
    public void delete(CtxfsUri uri) {
        Objects.requireNonNull(uri, "uri");
        // ctx_level 通过外键 ON DELETE CASCADE 一并清理；子节点不级联删除
        jdbcTemplate.update(DELETE_NODE_SQL, uri.toString());
    }

    @Override
    public void saveLevel(CtxfsUri uri, NodeLevel level) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(level, "level");
        jdbcTemplate.update(
                UPSERT_LEVEL_SQL,
                uri.toString(),
                level.level().name(),
                level.content(),
                level.tokenCount());
    }

    @Override
    public Optional<NodeLevel> findLevel(CtxfsUri uri, Level level) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(level, "level");
        List<NodeLevel> levels =
                jdbcTemplate.query(SELECT_LEVEL_SQL, this::mapLevel, uri.toString(), level.name());
        return levels.stream().findFirst();
    }

    private ContextNode mapNode(ResultSet rs, int rowNum) throws SQLException {
        CtxfsUri uri = CtxfsUri.parse(rs.getString("uri"));
        return new ContextNode(
                uri,
                NodeType.valueOf(rs.getString("node_type")),
                uri.parent().orElse(null),
                parseMetadata(rs.getString("metadata")),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"));
    }

    private NodeLevel mapLevel(ResultSet rs, int rowNum) throws SQLException {
        return new NodeLevel(
                Level.valueOf(rs.getString("level")),
                rs.getString("content"),
                rs.getInt("token_count"));
    }

    private Map<String, String> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> metadata = objectMapper.readValue(json, METADATA_TYPE);
            return metadata == null ? Map.of() : metadata;
        } catch (JsonProcessingException e) {
            throw new StorageException("Failed to parse node metadata: " + json, e);
        }
    }

    private String toJson(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new StorageException("Failed to serialize node metadata", e);
        }
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
