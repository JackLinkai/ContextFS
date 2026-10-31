package com.contextfs.retrieval.eval;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.contextfs.retrieval.engine.RetrievalItem;
import com.contextfs.retrieval.engine.RetrievalMode;
import com.contextfs.retrieval.engine.RetrievalResult;
import com.contextfs.retrieval.engine.RetrievalService;
import com.contextfs.retrieval.support.HashingEmbeddingClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 检索质量评测：固定语料 + 6 组查询，产出召回率 / 平均加载层级 / token 消耗基线。
 *
 * <p>语料为 10 节点（3 目录 + 7 文档），嵌入使用 {@link HashingEmbeddingClient}（字符 unigram + bigram
 * 哈希），查询复用语料中的关键短语，验证检索链路与质量下限。 基线指标写入 {@code target/retrieval-eval-baseline.json}，供后续真实嵌入模型对比。
 */
@DisplayName("检索质量评测集")
class RetrievalEvalTest {

    private static final String SCOPE = "global";
    private static final RetrievalConfig CONFIG = new RetrievalConfig(5, Level.L1, 0.2, 4096, 3);

    private static RetrievalService service;

    /** 查询 → 期望命中的文档 URI（目录命中算加分项，不计入召回率）。 */
    private static final List<String> QUERIES =
            List.of(
                    "如何进行网络搜索并获取网页摘要",
                    "用户界面主题偏好设置",
                    "write read ls find 接口使用方法",
                    "代码质量与缺陷检查",
                    "404 节点不存在的错误码含义",
                    "多 Agent 上下文管理平台项目背景");

    private static final List<String> EXPECTED_DOCS =
            List.of(
                    "ctxfs://global/skills/web_search.md",
                    "ctxfs://global/memories/preferences.md",
                    "ctxfs://global/docs/api_guide.md",
                    "ctxfs://global/skills/code_review.md",
                    "ctxfs://global/docs/error_codes.md",
                    "ctxfs://global/memories/project_brief.md");

    @BeforeAll
    static void setUp() {
        InMemoryNodeRepository repository = new InMemoryNodeRepository();
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        HashingEmbeddingClient embeddingClient = new HashingEmbeddingClient();
        Corpus corpus = new Corpus(repository, vectorStore, embeddingClient);
        corpus.build();
        service = new RetrievalService(repository, vectorStore, embeddingClient, CONFIG);
    }

    @Test
    @DisplayName("评测：recall@3 ≥ 0.6，并输出基线指标文件")
    void evaluate() throws IOException {
        assertThat(QUERIES).hasSameSizeAs(EXPECTED_DOCS);

        int topN = 3;
        double recallSum = 0;
        double levelSum = 0;
        long tokenSum = 0;
        long latencySum = 0;
        int itemCount = 0;
        List<String> perQueryJson = new ArrayList<>();

        for (int i = 0; i < QUERIES.size(); i++) {
            String query = QUERIES.get(i);
            String expected = EXPECTED_DOCS.get(i);
            RetrievalResult result =
                    service.retrieve(SCOPE, query, RetrievalMode.SEARCH, null, "eval-" + i);

            List<String> top3 =
                    result.items().stream().limit(topN).map(RetrievalItem::uri).toList();
            boolean hit = top3.contains(expected);
            recallSum += hit ? 1 : 0;
            levelSum +=
                    result.items().stream().mapToDouble(item -> levelWeight(item.level())).sum();
            itemCount += result.items().size();
            tokenSum += result.tokenUsage().promptTokens();
            latencySum += result.latencyMs();

            perQueryJson.add(
                    "{\"query\": \""
                            + query
                            + "\", \"expected\": \""
                            + expected
                            + "\", \"top3\": "
                            + jsonArray(top3)
                            + ", \"hit\": "
                            + hit
                            + "}");
        }

        double recallAt3 = recallSum / QUERIES.size();
        double avgLoadedLevel = itemCount == 0 ? 0 : levelSum / itemCount;
        double avgPromptTokens = (double) tokenSum / QUERIES.size();
        double avgLatencyMs = (double) latencySum / QUERIES.size();

        System.out.printf(
                "检索评测基线：recall@3=%.2f，平均加载层级=%.2f，平均 token=%.1f，平均耗时=%.1fms%n",
                recallAt3, avgLoadedLevel, avgPromptTokens, avgLatencyMs);
        assertThat(recallAt3).as("recall@3 应不低于 0.6").isGreaterThanOrEqualTo(0.6);

        String baseline =
                "{\n  \"queries\": "
                        + QUERIES.size()
                        + ",\n  \"recallAt3\": "
                        + recallAt3
                        + ",\n  \"avgLoadedLevel\": "
                        + avgLoadedLevel
                        + ",\n  \"avgPromptTokens\": "
                        + avgPromptTokens
                        + ",\n  \"avgLatencyMs\": "
                        + avgLatencyMs
                        + ",\n  \"perQuery\": [\n    "
                        + String.join(",\n    ", perQueryJson)
                        + "\n  ]\n}";
        Files.writeString(
                Path.of("target", "retrieval-eval-baseline.json"),
                baseline,
                StandardCharsets.UTF_8);
    }

    /** L0 → 0，L1 → 1，L2 → 2。 */
    private static double levelWeight(Level level) {
        return switch (level) {
            case L0 -> 0;
            case L1 -> 1;
            case L2 -> 2;
        };
    }

    private static String jsonArray(List<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").toList().toString();
    }

    /** 评测语料构建器：10 节点，目录只写 L0，文档写全 L0/L1/L2 并为 L0 建向量。 */
    private static final class Corpus {

        private final InMemoryNodeRepository repository;
        private final InMemoryVectorStore vectorStore;
        private final HashingEmbeddingClient embeddingClient;

        private Corpus(
                InMemoryNodeRepository repository,
                InMemoryVectorStore vectorStore,
                HashingEmbeddingClient embeddingClient) {
            this.repository = repository;
            this.vectorStore = vectorStore;
            this.embeddingClient = embeddingClient;
        }

        void build() {
            dir("skills", "技能目录：网络搜索、文件编辑与代码审查等技能说明。");
            doc(
                    "skills/web_search.md",
                    "网络搜索技能：调用搜索引擎检索网页，返回结果列表与页面摘要。",
                    "web_search 概览：入参 query 与 topK，出参为网页标题、链接与摘要。",
                    "web_search 完整文档：调用搜索引擎 API 检索网页，支持翻页、时间过滤与站点限定。");
            doc(
                    "skills/file_edit.md",
                    "文件编辑技能：读取本地文件内容，按行修改并保存写回。",
                    "file_edit 概览：支持按行号替换、追加与整文件重写。",
                    "file_edit 完整文档：读取文件、定位行、替换内容并写回磁盘的完整流程。");
            doc(
                    "skills/code_review.md",
                    "代码审查技能：检查代码质量、潜在缺陷与命名规范问题。",
                    "code_review 概览：静态检查与人工评审结合，输出问题清单。",
                    "code_review 完整文档：审查代码质量、潜在缺陷、命名规范与测试覆盖。");
            dir("memories", "记忆目录：保存用户偏好与项目背景。");
            doc(
                    "memories/preferences.md",
                    "用户偏好记忆：偏好中文回复、简洁风格与暗色主题界面。",
                    "偏好概览：语言、风格与主题三类偏好。",
                    "偏好完整记录：中文回复、简洁风格、暗色主题、Java 技术栈。");
            doc(
                    "memories/project_brief.md",
                    "项目背景记忆：ContextFS 是面向多 Agent 场景的上下文统一管理平台。",
                    "项目概览：ContextFS 的定位、目标用户与核心能力。",
                    "项目背景完整记录：ContextFS 为多 Agent 场景提供上下文统一管理。");
            dir("docs", "文档目录：REST API 使用指南与错误码说明。");
            doc(
                    "docs/api_guide.md",
                    "API 使用指南：write 写入、read 读取、ls 列目录与 find 查找接口。",
                    "API 概览：各接口的请求参数、响应结构与鉴权头说明。",
                    "API 完整文档：write/read/ls/tree/find 的请求响应示例与错误处理。");
            doc(
                    "docs/error_codes.md",
                    "错误码说明：400 参数错误、404 节点不存在、403 scope 越权。",
                    "错误码概览：4xx 客户端错误与 5xx 服务端错误分类。",
                    "错误码完整说明：400 参数错误、404 节点不存在、403 scope 越权、500 服务端异常。");
        }

        private void dir(String path, String l0) {
            upsert(path, NodeType.DIRECTORY, l0, null, null);
        }

        private void doc(String path, String l0, String l1, String l2) {
            upsert(path, NodeType.DOCUMENT, l0, l1, l2);
        }

        private void upsert(String path, NodeType type, String l0, String l1, String l2) {
            CtxfsUri uri = CtxfsUri.of(SCOPE, path);
            repository.save(
                    new ContextNode(
                            uri,
                            type,
                            uri.parent().orElse(null),
                            Map.of(),
                            Instant.now(),
                            Instant.now()));
            repository.saveLevel(uri, new NodeLevel(Level.L0, l0, Tokens.estimate(l0)));
            if (l1 != null) {
                repository.saveLevel(uri, new NodeLevel(Level.L1, l1, Tokens.estimate(l1)));
            }
            if (l2 != null) {
                repository.saveLevel(uri, new NodeLevel(Level.L2, l2, Tokens.estimate(l2)));
            }
            vectorStore.upsert(new VectorRecord(uri, Level.L0, l0, embeddingClient.embed(l0)));
        }
    }
}
