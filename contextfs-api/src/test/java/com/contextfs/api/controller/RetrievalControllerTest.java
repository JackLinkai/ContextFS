package com.contextfs.api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contextfs.api.common.GlobalExceptionHandler;
import com.contextfs.core.inmem.InMemoryNodeRepository;
import com.contextfs.core.inmem.InMemoryVectorStore;
import com.contextfs.core.model.ContextNode;
import com.contextfs.core.model.Level;
import com.contextfs.core.model.NodeLevel;
import com.contextfs.core.model.NodeType;
import com.contextfs.core.port.VectorRecord;
import com.contextfs.core.uri.CtxfsUri;
import com.contextfs.core.util.Tokens;
import com.contextfs.retrieval.engine.RetrievalConfig;
import com.contextfs.retrieval.engine.RetrievalService;
import com.contextfs.retrieval.support.HashingEmbeddingClient;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** RetrievalController 接口契约测试：InMemory 装配 + 哈希嵌入，不依赖外部服务。 */
@DisplayName("RetrievalController REST 接口")
class RetrievalControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        InMemoryNodeRepository repository = new InMemoryNodeRepository();
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        HashingEmbeddingClient embeddingClient = new HashingEmbeddingClient();
        seed(
                repository,
                vectorStore,
                embeddingClient,
                "global",
                "skills/web_search.md",
                "网络搜索技能：调用搜索引擎检索网页，返回结果列表与页面摘要。");
        seed(
                repository,
                vectorStore,
                embeddingClient,
                "user/10001",
                "notes/private.md",
                "私人笔记：网络搜索的账号与密钥。");
        RetrievalService service =
                new RetrievalService(
                        repository,
                        vectorStore,
                        embeddingClient,
                        new RetrievalConfig(5, Level.L1, 0.3, 4096, 3));
        mockMvc =
                MockMvcBuilders.standaloneSetup(new RetrievalController(service))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    private void seed(
            InMemoryNodeRepository repository,
            InMemoryVectorStore vectorStore,
            HashingEmbeddingClient embeddingClient,
            String scope,
            String path,
            String l0) {
        CtxfsUri uri = CtxfsUri.of(scope, path);
        repository.save(
                new ContextNode(
                        uri,
                        NodeType.DOCUMENT,
                        uri.parent().orElse(null),
                        Map.of(),
                        Instant.now(),
                        Instant.now()));
        repository.saveLevel(uri, new NodeLevel(Level.L0, l0, Tokens.estimate(l0)));
        vectorStore.upsert(new VectorRecord(uri, Level.L0, l0, embeddingClient.embed(l0)));
    }

    @Test
    @DisplayName("POST /retrieval/search 返回裸 JSON：traceId/mode/items/tokenUsage/latencyMs")
    void searchBareJsonContract() throws Exception {
        String body =
                """
                {
                  "scope": "global",
                  "query": "网络搜索 网页摘要",
                  "mode": "search",
                  "traceId": "trace-rest-1"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/retrieval/search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("trace-rest-1"))
                .andExpect(jsonPath("$.mode").value("SEARCH"))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.tokenUsage.promptTokens").isNumber())
                .andExpect(jsonPath("$.tokenUsage.completionTokens").value(0))
                .andExpect(jsonPath("$.latencyMs").isNumber());
    }

    @Test
    @DisplayName("X-ContextFS-Scope 头限定检索范围，不返回其他 scope 内容")
    void searchIsolatedByScopeHeader() throws Exception {
        String body = "{\"query\": \"网络搜索 账号 密钥\"}";

        mockMvc.perform(
                        post("/api/v1/retrieval/search")
                                .header(FsController.SCOPE_HEADER, "user/10001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items[*].uri")
                                .value(
                                        org.hamcrest.Matchers.hasItem(
                                                "ctxfs://user/10001/notes/private.md")));
    }

    @Test
    @DisplayName("请求体 scope 与 X-ContextFS-Scope 头冲突返回 403")
    void scopeConflictRejected() throws Exception {
        String body = "{\"scope\": \"global\", \"query\": \"网络搜索\"}";

        mockMvc.perform(
                        post("/api/v1/retrieval/search")
                                .header(FsController.SCOPE_HEADER, "user/10001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("query 缺失返回 400")
    void missingQueryRejected() throws Exception {
        String body = "{\"scope\": \"global\"}";

        mockMvc.perform(
                        post("/api/v1/retrieval/search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("非法 mode 返回 400")
    void invalidModeRejected() throws Exception {
        String body = "{\"query\": \"网络搜索\", \"mode\": \"vector\"}";

        mockMvc.perform(
                        post("/api/v1/retrieval/search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("非法 maxLevel 返回 400")
    void invalidMaxLevelRejected() throws Exception {
        String body = "{\"query\": \"网络搜索\", \"maxLevel\": \"L3\", \"scope\": \"global\"}";

        mockMvc.perform(
                        post("/api/v1/retrieval/search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest());
    }
}
