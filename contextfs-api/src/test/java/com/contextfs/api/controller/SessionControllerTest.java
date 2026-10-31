package com.contextfs.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contextfs.api.common.GlobalExceptionHandler;
import com.contextfs.core.inmem.InMemoryNodeRepository;
import com.contextfs.core.inmem.InMemoryVectorStore;
import com.contextfs.core.model.Level;
import com.contextfs.memory.pipeline.MemoryExtractor;
import com.contextfs.memory.pipeline.MemoryPipelineService;
import com.contextfs.retrieval.support.HashingEmbeddingClient;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** SessionController 接口契约测试：InMemory 装配，不依赖外部服务。 */
@DisplayName("SessionController REST 接口")
class SessionControllerTest {

    private static final String COMMIT_BODY =
            """
            {
              "agentId": "coder",
              "userId": "10001",
              "messages": [
                {"role": "user", "content": "以后回答尽量简洁，代码示例优先使用 Java。"},
                {"role": "assistant", "content": "好的，我会优先使用 Java 并保持简洁。"}
              ]
            }
            """;

    private InMemoryNodeRepository repository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = new InMemoryNodeRepository();
        MemoryPipelineService service =
                new MemoryPipelineService(
                        repository,
                        new HashingEmbeddingClient(),
                        new InMemoryVectorStore(),
                        new MemoryExtractor(),
                        true,
                        MemoryPipelineService.SYNC_EXECUTOR);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new SessionController(service))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    @DisplayName("POST /sessions/{id}/commit 同步返回归档 URI 与记忆变更明细")
    void commitSynchronous() throws Exception {
        mockMvc.perform(
                        post("/api/v1/sessions/sess_abc123/commit")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(COMMIT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sessionId").value("sess_abc123"))
                .andExpect(
                        jsonPath("$.data.archivedUri")
                                .value("ctxfs://session/sess_abc123/archive.md"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.changes[0].decision").value("CREATED"))
                .andExpect(jsonPath("$.data.changes[0].type").value("PREFERENCE"))
                .andExpect(
                        jsonPath("$.data.changes[0].uri")
                                .value("ctxfs://user/10001/memories/preference-1.md"));

        // 验收：MEMORY 节点已生成（含三层内容）
        assertThat(
                        repository.findLevel(
                                com.contextfs.core.uri.CtxfsUri.of(
                                        "user/10001", "memories/preference-1.md"),
                                Level.L2))
                .isPresent();
    }

    @Test
    @DisplayName("异步管线返回 ACCEPTED，提取后台执行")
    void commitAsyncAccepted() throws Exception {
        InMemoryNodeRepository asyncRepository = new InMemoryNodeRepository();
        AtomicReference<MemoryPipelineService> holder = new AtomicReference<>();
        MemoryPipelineService asyncService =
                new MemoryPipelineService(
                        asyncRepository,
                        new HashingEmbeddingClient(),
                        new InMemoryVectorStore(),
                        new MemoryExtractor(),
                        true,
                        java.util.concurrent.Executors.newSingleThreadExecutor());
        holder.set(asyncService);
        MockMvc asyncMockMvc =
                MockMvcBuilders.standaloneSetup(new SessionController(asyncService))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();

        asyncMockMvc
                .perform(
                        post("/api/v1/sessions/sess_async/commit")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(COMMIT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.changes").isEmpty());
    }

    @Test
    @DisplayName("X-ContextFS-Scope 与记忆目标 scope 冲突返回 403")
    void scopeConflictRejected() throws Exception {
        mockMvc.perform(
                        post("/api/v1/sessions/sess_1/commit")
                                .header(FsController.SCOPE_HEADER, "user/10002")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(COMMIT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("X-ContextFS-Scope 与记忆目标 scope 一致时放行")
    void scopeMatchAllowed() throws Exception {
        mockMvc.perform(
                        post("/api/v1/sessions/sess_1/commit")
                                .header(FsController.SCOPE_HEADER, "user/10001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(COMMIT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("messages 缺失或 role 空白返回 400")
    void validationFailures() throws Exception {
        mockMvc.perform(
                        post("/api/v1/sessions/sess_1/commit")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"agentId\": \"coder\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(
                        post("/api/v1/sessions/sess_1/commit")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"messages\": [{\"role\": \"\", \"content\": \"内容\"}]}"))
                .andExpect(status().isBadRequest());
    }
}
