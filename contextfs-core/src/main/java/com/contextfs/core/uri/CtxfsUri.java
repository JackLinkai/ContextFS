package com.contextfs.core.uri;

import com.contextfs.core.exception.InvalidUriException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code ctxfs://{scope}/{path}} 的不可变值对象。
 *
 * <p>scope 仅允许 {@code global}、{@code user/{id}}、{@code agent/{id}}、{@code session/{id}} 四种
 * 形式；path 会被规范化：折叠连续分隔符、去除首尾分隔符、拒绝 {@code .} 与 {@code ..}。
 */
public final class CtxfsUri {

    public static final String SCHEME = "ctxfs://";
    /** path 最大深度，与配置 contextfs.fs.max-tree-depth 对应。 */
    public static final int MAX_PATH_DEPTH = 8;

    private static final String GLOBAL_SCOPE = "global";

    private final String scope;
    private final String path;

    private CtxfsUri(String scope, String path) {
        this.scope = scope;
        this.path = path;
    }

    /**
     * 按规范化规则构造 URI。
     *
     * @param scope 归属域，如 {@code global}、{@code user/10001}
     * @param path  scope 内路径，空串表示 scope 根节点
     */
    public static CtxfsUri of(String scope, String path) {
        String normalizedScope = normalizeScope(scope);
        String normalizedPath = normalizePath(path);
        return new CtxfsUri(normalizedScope, normalizedPath);
    }

    /** 解析完整 URI 字符串，如 {@code ctxfs://user/10001/memories/preferences.md}。 */
    public static CtxfsUri parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidUriException("URI must not be blank");
        }
        if (!raw.startsWith(SCHEME)) {
            throw new InvalidUriException("URI must start with '" + SCHEME + "': " + raw);
        }
        String rest = raw.substring(SCHEME.length());
        if (rest.isEmpty()) {
            throw new InvalidUriException("URI scope must not be empty: " + raw);
        }
        List<String> segments = new ArrayList<>(Arrays.asList(rest.split("/")));
        String first = segments.get(0);
        if (GLOBAL_SCOPE.equals(first)) {
            segments.remove(0);
            return of(GLOBAL_SCOPE, String.join("/", segments));
        }
        if ("user".equals(first) || "agent".equals(first) || "session".equals(first)) {
            if (segments.size() < 2 || segments.get(1).isEmpty()) {
                throw new InvalidUriException(
                        "Scope '" + first + "' requires a non-empty id: " + raw);
            }
            String scope = first + "/" + segments.get(1);
            segments.subList(0, 2).clear();
            return of(scope, String.join("/", segments));
        }
        throw new InvalidUriException("Unknown scope '" + first + "' in URI: " + raw);
    }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            throw new InvalidUriException("Scope must not be blank");
        }
        if (GLOBAL_SCOPE.equals(scope)) {
            return GLOBAL_SCOPE;
        }
        int idx = scope.indexOf('/');
        if (idx <= 0 || idx == scope.length() - 1) {
            throw new InvalidUriException(
                    "Scope must be 'global' or '{kind}/{id}' where kind is user|agent|session: "
                            + scope);
        }
        String kind = scope.substring(0, idx);
        String id = scope.substring(idx + 1);
        if (!("user".equals(kind) || "agent".equals(kind) || "session".equals(kind))) {
            throw new InvalidUriException(
                    "Scope kind must be one of global|user|agent|session: " + scope);
        }
        if (id.contains("/") || containsIllegalChar(id)) {
            throw new InvalidUriException("Scope id contains illegal character: " + scope);
        }
        return scope;
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (".".equals(segment) || "..".equals(segment)) {
                throw new InvalidUriException("Path segment '" + segment + "' is not allowed");
            }
            if (containsIllegalChar(segment)) {
                throw new InvalidUriException("Path segment contains illegal character: " + segment);
            }
            kept.add(segment);
        }
        if (kept.size() > MAX_PATH_DEPTH) {
            throw new InvalidUriException(
                    "Path depth exceeds limit " + MAX_PATH_DEPTH + ": " + path);
        }
        return String.join("/", kept);
    }

    private static boolean containsIllegalChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    /** scope，如 {@code global}、{@code user/10001}。 */
    public String scope() {
        return scope;
    }

    /** scope 内路径，根节点为空串。 */
    public String path() {
        return path;
    }

    /** 是否为 scope 根节点。 */
    public boolean isRoot() {
        return path.isEmpty();
    }

    /** path 段数，根节点为 0。 */
    public int depth() {
        return path.isEmpty() ? 0 : path.split("/").length;
    }

    /** 最后一个路径段；根节点取 scope 的最后一段（如 {@code user/10001} 的 {@code 10001}）。 */
    public String name() {
        if (!path.isEmpty()) {
            return path.substring(path.lastIndexOf('/') + 1);
        }
        int idx = scope.lastIndexOf('/');
        return idx < 0 ? scope : scope.substring(idx + 1);
    }

    /** 父节点 URI；scope 根节点无父节点。 */
    public Optional<CtxfsUri> parent() {
        if (isRoot()) {
            return Optional.empty();
        }
        int idx = path.lastIndexOf('/');
        String parentPath = idx < 0 ? "" : path.substring(0, idx);
        return Optional.of(new CtxfsUri(scope, parentPath));
    }

    /** 返回当前节点下名为 {@code name} 的子节点 URI。 */
    public CtxfsUri child(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty() || name.contains("/")) {
            throw new InvalidUriException("Invalid child name: " + name);
        }
        return new CtxfsUri(scope, path.isEmpty() ? name : path + "/" + name);
    }

    /** 规范形式：{@code ctxfs://{scope}[/{path}]}。 */
    @Override
    public String toString() {
        return path.isEmpty() ? SCHEME + scope : SCHEME + scope + "/" + path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CtxfsUri that)) {
            return false;
        }
        return scope.equals(that.scope) && path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, path);
    }
}
