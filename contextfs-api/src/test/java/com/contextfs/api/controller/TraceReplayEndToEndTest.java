package com.contextfs.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.contextfs.observability.log.InMemoryTraceLogStore;
import com.contextfs.observability.log.RetrievalLogEntry;
import com.contextfs.observability.log.TraceLogStore;
import com.contextfs.observability.trace.TraceSpanFilter;
import com.contextfs.retrieval.engine.RetrievalConfig;
import com.contextfs.retrieval.engine.RetrievalService;
import com.contextfs.retrieval.support.HashingEmbeddingClient;
import java.time.Instant;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 阶段 7 验收测试：一次 search 后通过 traces API 完整回放（入口 span + retrieval_log），traceId 贯穿。 */
@DisplayName("检索链路端到端回放（阶段 7 验收）")
class TraceReplayEndToEndTest {

    private static final String TRACE_ID = "trace-e2e-001";

    private InMemoryNodeRepository repository;
    private TraceLogStore traceLogStore;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = new InMemoryNodeRepository();
        traceLogStore = new InMemoryTraceLogStore();
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        HashingEmbeddingClient embeddingClient = new HashingEmbeddingClient();
        seedCorpus(vectorStore, embeddingClient);
        RetrievalService retrievalService =
                new RetrievalService(
                        repository,
                        vectorStore,
                        embeddingClient,
                        new RetrievalConfig(5, Level.L1, 0.3, 4096, 3),
                        traceLogStore);
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new RetrievalController(retrievalService),
                                new TraceController(traceLogStore))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .addFilters(new TraceSpanFilter(traceLogStore))
                        .build();
    }

    @Test
    @DisplayName("search 后按 traceId 回放：入口 span 与检索日志可完整还原决策链")
    void searchThenReplayTrace() throws Exception {
        String requestBody =
                """
                {
                  "scope": "global",
                  "query": "网络搜索 技能",
                  "mode": "search",
                  "traceId": "%s"
                }
                """
                        .formatted(TRACE_ID);

        // 1. 执行一次检索（traceId 同时出现在请求头与请求体，二者一致）
        mockMvc.perform(
                        post("/api/v1/retrieval/search")
                                .header(TraceSpanFilter.TRACE_HEADER, TRACE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.items.length()").value(Matchers.greaterThan(0)));

        // 2. 回放：入口 span（REST 过滤器记录）+ retrieval_log（检索引擎记录）
        mockMvc.perform(get("/api/v1/traces/{traceId}", TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.traceId").value(TRACE_ID))
                // 入口 span：HTTP POST /api/v1/retrieval/search，HTTP 200
                .andExpect(jsonPath("$.data.spans.length()").value(1))
                .andExpect(
                        jsonPath("$.data.spans[0].operation")
                                .value("HTTP POST /api/v1/retrieval/search"))
                .andExpect(jsonPath("$.data.spans[0].status").value("OK"))
                .andExpect(
                        jsonPath("$.data.spans[0].attributes['http.path']")
                                .value("/api/v1/retrieval/search"))
                // 检索日志结构：scope / query / mode / status
                .andExpect(jsonPath("$.data.retrievalLogs.length()").value(1))
                .andExpect(jsonPath("$.data.retrievalLogs[0].scope").value("global"))
                .andExpect(jsonPath("$.data.retrievalLogs[0].query").value("网络搜索 技能"))
                .andExpect(jsonPath("$.data.retrievalLogs[0].mode").value("search"))
                .andExpect(jsonPath("$.data.retrievalLogs[0].status").value("OK"))
                .andExpect(jsonPath("$.data.retrievalLogs[0].latencyMs").isNumber());

        // 3. 细节断言：命中 URI、加载层级与 token 消耗完整可回放
        RetrievalLogEntry log = traceLogStore.retrievalLogs(TRACE_ID).get(0);
        assertThat(log.retrievedUris()).isNotEmpty();
        assertThat(log.retrievedUris()).contains("ctxfs://global/skills/web_search.md");
        assertThat(log.levels()).hasSameSizeAs(log.retrievedUris());
        assertThat(log.levels()).doesNotContainNull();
        assertThat(log.promptTokens()).isPositive();
        assertThat(log.completionTokens()).isZero();
        assertThat(log.spanId()).hasSize(16);
    }

    @Test
    @DisplayName("traceId 仅出现在请求体时回放同样生效（头缺省场景）")
    void searchWithBodyTraceIdOnly() throws Exception {
        String requestBody =
                """
                {
                  "scope": "global",
                  "query": "文件编辑",
                  "mode": "find",
                  "traceId": "trace-body-only"
                }
                """;
        mockMvc.perform(
                        post("/api/v1/retrieval/search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("trace-body-only"));

        mockMvc.perform(get("/api/v1/traces/{traceId}", "trace-body-only"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retrievalLogs[0].mode").value("find"));
        assertThat(traceLogStore.toolCallLogs("trace-body-only")).isEmpty();
    }

    private void seedCorpus(InMemoryVectorStore vectorStore, HashingEmbeddingClient embedding) {
        doc(
                vectorStore,
                embedding,
                "global",
                "skills",
                NodeType.DIRECTORY,
                "技能目录：网络搜索、文件编辑等技能说明。",
                null);
        doc(
                vectorStore,
                embedding,
                "global",
                "skills/web_search.md",
                NodeType.DOCUMENT,
                "网络搜索技能：调用搜索引擎检索网页，返回结果列表与页面摘要。",
                "web_search 完整文档：调用搜索引擎 API 检索网页，支持翻页与站点限定。");
        doc(
                vectorStore,
                embedding,
                "global",
                "skills/file_edit.md",
                NodeType.DOCUMENT,
                "文件编辑技能：读取本地文件内容，按行修改并保存写回。",
                "file_edit 完整文档：读取文件、替换内容并写回磁盘。");
    }

    /** 写入节点（含父目录），保存 L0/L1 内容并写入 L0 向量。 */
    private void doc(
            InMemoryVectorStore vectorStore,
            HashingEmbeddingClient embedding,
            String scope,
            String path,
            NodeType type,
            String l0,
            String l2) {
        CtxfsUri uri = CtxfsUri.of(scope, path);
        var parent = uri.parent();
        while (parent.isPresent() && !repository.exists(parent.get())) {
            repository.save(
                    new ContextNode(
                            parent.get(),
                            NodeType.DIRECTORY,
                            parent.get().parent().orElse(null),
                            Map.of(),
                            Instant.now(),
                            Instant.now()));
            parent = parent.get().parent();
        }
        repository.save(
                new ContextNode(
                        uri,
                        type,
                        uri.parent().orElse(null),
                        Map.of(),
                        Instant.now(),
                        Instant.now()));
        repository.saveLevel(uri, new NodeLevel(Level.L0, l0, Tokens.estimate(l0)));
        if (l2 != null) {
            repository.saveLevel(uri, new NodeLevel(Level.L1, l2, Tokens.estimate(l2)));
            repository.saveLevel(uri, new NodeLevel(Level.L2, l2, Tokens.estimate(l2)));
        }
        vectorStore.upsert(new VectorRecord(uri, Level.L0, l0, embedding.embed(l0)));
    }
}
