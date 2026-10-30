package com.contextfs.core.port;

import com.contextfs.core.model.ContextNode;
import com.contextfs.core.model.Level;
import com.contextfs.core.model.NodeLevel;
import com.contextfs.core.uri.CtxfsUri;
import java.util.List;
import java.util.Optional;

/** 节点树与层级内容的持久化端口，由 PostgreSQL 适配实现。 */
public interface NodeRepository {

    /** 保存或整体覆盖节点（不包含层级内容）。 */
    void save(ContextNode node);

    Optional<ContextNode> findByUri(CtxfsUri uri);

    boolean exists(CtxfsUri uri);

    /** 列出直接子节点（ls），按名称排序。 */
    List<ContextNode> listChildren(CtxfsUri parent);

    /**
     * 返回以 root 为根的子树（含 root 自身），最多向下展开 {@code maxDepth} 层；maxDepth=0 仅返回
     * root。
     */
    List<ContextNode> listSubtree(CtxfsUri root, int maxDepth);

    /** 删除单个节点及其层级内容，不级联删除子节点。 */
    void delete(CtxfsUri uri);

    /** 保存或覆盖节点的某一层内容。 */
    void saveLevel(CtxfsUri uri, NodeLevel level);

    Optional<NodeLevel> findLevel(CtxfsUri uri, Level level);
}
