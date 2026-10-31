package com.contextfs.api.controller;

import com.contextfs.api.common.ApiResponse;
import com.contextfs.api.dto.SessionCommitRequest;
import com.contextfs.api.dto.SessionCommitResultDto;
import com.contextfs.core.exception.ScopeViolationException;
import com.contextfs.memory.pipeline.CommitOutcome;
import com.contextfs.memory.pipeline.MemoryPipelineService;
import com.contextfs.memory.pipeline.SessionMessage;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Session 记忆管线 REST 接口：/api/v1/sessions/*。 */
@RestController
public class SessionController {

    /** 记忆管线异步提取时的受理状态。 */
    static final String STATUS_ACCEPTED = "ACCEPTED";

    /** 记忆管线同步完成时的状态。 */
    static final String STATUS_COMPLETED = "COMPLETED";

    private final MemoryPipelineService memoryPipelineService;

    public SessionController(MemoryPipelineService memoryPipelineService) {
        this.memoryPipelineService = memoryPipelineService;
    }

    /**
     * 提交会话：归档消息为 DOCUMENT 节点，并（启用时）异步提取记忆。
     *
     * <p>scope 隔离：{@code X-ContextFS-Scope} 头存在时，请求的记忆目标 scope（user/{userId} 或 agent/{agentId}，均缺省为
     * global）必须与之相等，否则返回 403。
     */
    @PostMapping("/api/v1/sessions/{sessionId}/commit")
    public ApiResponse<SessionCommitResultDto> commit(
            @PathVariable String sessionId,
            @Valid @RequestBody SessionCommitRequest request,
            @RequestHeader(value = FsController.SCOPE_HEADER, required = false)
                    String allowedScope) {
        String targetScope = targetScope(request);
        if (allowedScope != null && !allowedScope.isBlank() && !allowedScope.equals(targetScope)) {
            throw new ScopeViolationException(
                    "Memory target scope '"
                            + targetScope
                            + "' is not allowed for caller scope '"
                            + allowedScope
                            + "'");
        }
        List<SessionMessage> messages =
                request.messages().stream()
                        .map(message -> new SessionMessage(message.role(), message.content()))
                        .toList();
        if (memoryPipelineService.isAsync()) {
            memoryPipelineService.commitAsync(
                    sessionId, request.agentId(), request.userId(), messages);
            return ApiResponse.ok(
                    new SessionCommitResultDto(
                            sessionId,
                            "ctxfs://session/" + sessionId + "/archive.md",
                            STATUS_ACCEPTED,
                            List.of()));
        }
        CommitOutcome outcome =
                memoryPipelineService.commit(
                        sessionId, request.agentId(), request.userId(), messages);
        return ApiResponse.ok(toResult(sessionId, outcome, STATUS_COMPLETED));
    }

    /** 记忆目标 scope：userId 优先，其次 agentId，均缺省为 global。 */
    private String targetScope(SessionCommitRequest request) {
        if (request.userId() != null && !request.userId().isBlank()) {
            return "user/" + request.userId();
        }
        if (request.agentId() != null && !request.agentId().isBlank()) {
            return "agent/" + request.agentId();
        }
        return "global";
    }

    private SessionCommitResultDto toResult(
            String sessionId, CommitOutcome outcome, String status) {
        List<SessionCommitResultDto.MemoryChangeDto> changes =
                outcome.changes().stream()
                        .map(
                                change ->
                                        new SessionCommitResultDto.MemoryChangeDto(
                                                change.uri(),
                                                change.type(),
                                                change.decision(),
                                                change.similarity()))
                        .toList();
        return new SessionCommitResultDto(sessionId, outcome.archivedUri(), status, changes);
    }
}
