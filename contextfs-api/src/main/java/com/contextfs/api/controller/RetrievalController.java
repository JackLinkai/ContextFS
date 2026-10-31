package com.contextfs.api.controller;

import com.contextfs.api.dto.RetrievalSearchRequest;
import com.contextfs.core.exception.ScopeViolationException;
import com.contextfs.core.model.Level;
import com.contextfs.retrieval.engine.RetrievalConfig;
import com.contextfs.retrieval.engine.RetrievalMode;
import com.contextfs.retrieval.engine.RetrievalResult;
import com.contextfs.retrieval.engine.RetrievalService;
import jakarta.validation.Valid;
import java.util.Locale;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 目录递归检索 REST 接口：/api/v1/retrieval/*，响应为裸 JSON（无 envelope）。 */
@RestController
@RequestMapping("/api/v1/retrieval")
public class RetrievalController {

    private final RetrievalService retrievalService;

    public RetrievalController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    /**
     * 检索入口。scope 优先级：{@code X-ContextFS-Scope} 头 &gt; 请求体 scope（缺省 {@code global}）； 请求体 scope
     * 与头不一致时拒绝（403）。配置项缺省时回退服务端默认值。
     */
    @PostMapping("/search")
    public RetrievalResult search(
            @Valid @RequestBody RetrievalSearchRequest request,
            @RequestHeader(value = FsController.SCOPE_HEADER, required = false)
                    String allowedScope) {
        String effectiveScope = effectiveScope(request.scope(), allowedScope);
        RetrievalMode mode = parseMode(request.mode());
        Level maxLevel = parseMaxLevel(request.maxLevel());
        RetrievalConfig base = retrievalService.defaultConfig();
        RetrievalConfig effective =
                new RetrievalConfig(
                        request.topKDir() != null ? request.topKDir() : base.topKDir(),
                        maxLevel != null ? maxLevel : base.maxLevel(),
                        request.scoreThreshold() != null
                                ? request.scoreThreshold()
                                : base.scoreThreshold(),
                        request.tokenBudget() != null ? request.tokenBudget() : base.tokenBudget(),
                        request.maxDepth() != null ? request.maxDepth() : base.maxDepth());
        return retrievalService.retrieve(
                effectiveScope, request.query(), mode, effective, request.traceId());
    }

    private String effectiveScope(String requestScope, String allowedScope) {
        if (allowedScope != null && !allowedScope.isBlank()) {
            if (requestScope != null
                    && !requestScope.isBlank()
                    && !allowedScope.equals(requestScope)) {
                throw new ScopeViolationException(
                        "Request scope '"
                                + requestScope
                                + "' does not match caller scope '"
                                + allowedScope
                                + "'");
            }
            return allowedScope;
        }
        return requestScope == null || requestScope.isBlank() ? "global" : requestScope;
    }

    private RetrievalMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return RetrievalMode.SEARCH;
        }
        return RetrievalMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
    }

    private Level parseMaxLevel(String maxLevel) {
        if (maxLevel == null || maxLevel.isBlank()) {
            return null;
        }
        return Level.valueOf(maxLevel.trim().toUpperCase(Locale.ROOT));
    }
}
