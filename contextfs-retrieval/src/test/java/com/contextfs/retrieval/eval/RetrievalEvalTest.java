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
 * 检索质量评测：固定语料 + 6 组查询，产出召回率 / 平均加载层级 / token 消耗与降幅 / 延迟分位基线。
 *
 * <p>语料为 10 节点（3 目录 + 7 文档），嵌入使用 {@link HashingEmbeddingClient}（字符 unigram + bigram
 * 哈希），查询复用语料中的关键短语，验证检索链路与质量下限。 token 对比口径：同一命中集合下「三层按需加载」的注入 token 与
 * 「直接注入命中节点 L2 原文」的 token 求降幅；延迟对每查询多次执行取 P50 / P95。 基线指标写入 {@code
 * target/retrieval-eval-baseline.json}，供后续真实嵌入模型对比。
 */
@DisplayName("检索质量评测集")
class RetrievalEvalTest {

    private static final String SCOPE = "global";
    private static final RetrievalConfig CONFIG = new RetrievalConfig(5, Level.L1, 0.2, 4096, 3);

    private static RetrievalService service;
    private static InMemoryNodeRepository repository;

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
        repository = new InMemoryNodeRepository();
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        HashingEmbeddingClient embeddingClient = new HashingEmbeddingClient();
        Corpus corpus = new Corpus(repository, vectorStore, embeddingClient);
        corpus.build();
        service = new RetrievalService(repository, vectorStore, embeddingClient, CONFIG);
    }

    @Test
    @DisplayName("评测：recall@3 ≥ 0.6、三层加载 token 降幅 ≥ 40%、延迟基线，输出指标文件")
    void evaluate() throws IOException {
        assertThat(QUERIES).hasSameSizeAs(EXPECTED_DOCS);

        int topN = 3;
        double recallSum = 0;
        double levelSum = 0;
        long tokenSum = 0;
        long l2TokenSum = 0;
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
            // 对照组：同一命中集合下，不使用三层加载而直接注入各节点 L2 原文的 token 量
            long l2Tokens =
                    result.items().stream()
                            .mapToLong(item -> l2TokensOf(item.uri()))
                            .sum();
            l2TokenSum += l2Tokens;
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
                            + ", \"loadedTokens\": "
                            + result.tokenUsage().promptTokens()
                            + ", \"l2FullTokens\": "
                            + l2Tokens
                            + "}");
        }

        double recallAt3 = recallSum / QUERIES.size();
        double avgLoadedLevel = itemCount == 0 ? 0 : levelSum / itemCount;
        double avgPromptTokens = (double) tokenSum / QUERIES.size();
        double avgLatencyMs = (double) latencySum / QUERIES.size();
        // 三层加载相对直接注入 L2 的 token 降幅
        double tokenReduction =
                l2TokenSum == 0 ? 0 : 1.0 - (double) tokenSum / l2TokenSum;

        // 延迟分位：预热 1 轮后每查询执行 30 次
        List<Long> latencies = new ArrayList<>();
        for (String query : QUERIES) {
            service.retrieve(SCOPE, query, RetrievalMode.SEARCH, null, "eval-warmup");
            for (int i = 0; i < 30; i++) {
                latencies.add(
                        service
                                .retrieve(SCOPE, query, RetrievalMode.SEARCH, null, "eval-lat")
                                .latencyMs());
            }
        }
        latencies.sort(Long::compare);
        long p50 = latencies.get(latencies.size() / 2);
        long p95 = latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1);

        System.out.printf(
                "检索评测基线：recall@3=%.2f，平均加载层级=%.2f，平均 token=%.1f（直接注入 L2=%.1f，降幅=%.1f%%），"
                        + "P50=%dms，P95=%dms%n",
                recallAt3,
                avgLoadedLevel,
                avgPromptTokens,
                (double) l2TokenSum / QUERIES.size(),
                tokenReduction * 100,
                p50,
                p95);
        assertThat(recallAt3).as("recall@3 应不低于 0.6").isGreaterThanOrEqualTo(0.6);
        assertThat(tokenReduction)
                .as("三层加载相对直接注入 L2 的 token 降幅应不低于 40%")
                .isGreaterThanOrEqualTo(0.4);

        String baseline =
                "{\n  \"queries\": "
                        + QUERIES.size()
                        + ",\n  \"recallAt3\": "
                        + recallAt3
                        + ",\n  \"avgLoadedLevel\": "
                        + avgLoadedLevel
                        + ",\n  \"avgLoadedTokens\": "
                        + avgPromptTokens
                        + ",\n  \"avgL2FullTokens\": "
                        + (double) l2TokenSum / QUERIES.size()
                        + ",\n  \"tokenReductionVsL2\": "
                        + tokenReduction
                        + ",\n  \"latencyP50Ms\": "
                        + p50
                        + ",\n  \"latencyP95Ms\": "
                        + p95
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

    /** 节点 L2 原文 token 数；目录节点无 L2 视为 0。 */
    private static long l2TokensOf(String uriText) {
        return repository
                .findLevel(CtxfsUri.parse(uriText), Level.L2)
                .map(NodeLevel::content)
                .map(Tokens::estimate)
                .map(Long::valueOf)
                .orElse(0L);
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
                    "web_search 完整文档：本技能封装搜索引擎调用，入参 query 为检索关键词，topK 控制返回条数，默认 5 条。"
                            + "检索流程分为三步：构造查询串、调用搜索 API、解析结果列表，返回字段包括网页标题、链接、页面摘要与更新时间。"
                            + "支持翻页参数 page 与每页大小，支持时间过滤（近一天、近一周、近一月）与站点限定 site 参数。"
                            + "使用建议：query 应包含核心实体与意图词，避免过长；对时效性问题显式指定时间过滤；摘要仅用于初筛，"
                            + "引用结论前需打开原文核对。常见错误：429 触发限流需退避重试，403 多为站点拒绝抓取。");
            doc(
                    "skills/file_edit.md",
                    "文件编辑技能：读取本地文件内容，按行修改并保存写回。",
                    "file_edit 概览：支持按行号替换、追加与整文件重写。",
                    "file_edit 完整文档：本技能用于读取本地文件、按行定位并修改内容后写回磁盘。"
                            + "读取阶段支持整文件读入与指定行区间读取两种方式，编码默认 UTF-8。"
                            + "修改操作支持三种模式：按行号替换、在指定行后追加、整文件重写；替换内容中的换行符按原文件风格保留。"
                            + "写回前自动生成备份文件，写回失败时回滚到备份。行号从 1 开始，负数表示从文件末尾倒数。"
                            + "典型场景：修复配置项、批量调整日志级别、插入新的依赖声明。"
                            + "注意事项：二进制文件不支持按行编辑；超过 10MB 的文件建议分段读取；并发编辑同一文件时以最后写回者为准。");
            doc(
                    "skills/code_review.md",
                    "代码审查技能：检查代码质量、潜在缺陷与命名规范问题。",
                    "code_review 概览：静态检查与人工评审结合，输出问题清单。",
                    "code_review 完整文档：本技能对给定代码执行静态检查并输出问题清单，检查维度包括代码质量、潜在缺陷、命名规范与测试覆盖。"
                            + "质量维度关注重复代码、过长方法与深嵌套；缺陷维度关注空指针、资源未关闭、并发可见性与边界越界；"
                            + "命名维度检查类名大驼峰、方法名小驼峰与常量大写；覆盖维度统计分支覆盖并标记缺失用例。"
                            + "输出格式为逐条问题：文件、行号、严重级别与修复建议，严重级别分为 blocker、major、minor 三档，"
                            + "blocker 必须修复后才能合并。建议将本技能接入合并请求流水线，在人工评审前先跑一轮静态检查以节省评审时间。");
            dir("memories", "记忆目录：保存用户偏好与项目背景。");
            doc(
                    "memories/preferences.md",
                    "用户偏好记忆：偏好中文回复、简洁风格与暗色主题界面。",
                    "偏好概览：语言、风格与主题三类偏好。",
                    "偏好完整记录：用户明确表达过的长期偏好集合。语言偏好：默认使用中文回复，技术名词保留英文原文。"
                            + "风格偏好：回答尽量简洁，先给结论再给依据，代码示例优先使用 Java，附必要注释。"
                            + "界面偏好：编辑器与终端使用暗色主题，字体大小 14。"
                            + "技术栈偏好：后端 Java 21 与 Spring Boot，构建工具 Maven，数据库 PostgreSQL；前端仅在需要时使用 React。"
                            + "工作偏好：任务拆分后逐步执行，每个阶段完成后主动汇报；提交信息使用中文 conventional commit 格式。"
                            + "以上偏好随会话持续累积，冲突时以最新表述为准。");
            doc(
                    "memories/project_brief.md",
                    "项目背景记忆：ContextFS 是面向多 Agent 场景的上下文统一管理平台。",
                    "项目概览：ContextFS 的定位、目标用户与核心能力。",
                    "项目背景完整记录：ContextFS 是面向多 Agent 场景的上下文统一管理平台，"
                            + "目标是为 Agent 长期运行提供可检索、可分层加载的上下文存储。"
                            + "核心能力包括三层上下文模型（L0 摘要、L1 概览、L2 原文）、URI 寻址（ctxfs://scope/path）、"
                            + "语义检索与词法检索双通道、会话归档与记忆提取合并。目标用户为构建 Agent 应用的开发者与平台团队。"
                            + "设计原则：端口-适配器架构，核心域不依赖具体存储；所有能力通过 REST API 暴露；"
                            + "可观测性贯穿检索与记忆管线。当前进展：核心存储、检索引擎、记忆管线与可观测回放已完成，评测与文档收尾中。");
            dir("docs", "文档目录：REST API 使用指南与错误码说明。");
            doc(
                    "docs/api_guide.md",
                    "API 使用指南：write 写入、read 读取、ls 列目录与 find 查找接口。",
                    "API 概览：各接口的请求参数、响应结构与鉴权头说明。",
                    "API 完整文档：所有接口前缀为 /api/v1，响应统一包裹在 ApiResponse（code=0 表示成功）。"
                            + "write 接口写入节点内容，请求体包含 uri 与 content，成功返回节点元数据。"
                            + "read 接口按 uri 读取指定 level 的内容，支持 level 参数（l0/l1/l2，默认 l1）。"
                            + "ls 接口列目录子节点，支持 depth 参数控制展开深度；tree 接口返回目录树结构；find 接口按关键词精确查找。"
                            + "search 接口语义检索，请求体包含 query、topN、mode。"
                            + "session 接口归档会话消息并触发记忆提取，支持同步与异步两种模式。"
                            + "错误响应遵循统一错误码：参数错误 400，节点不存在 404，scope 越权 403。");
            doc(
                    "docs/error_codes.md",
                    "错误码说明：400 参数错误、404 节点不存在、403 scope 越权。",
                    "错误码概览：4xx 客户端错误与 5xx 服务端错误分类。",
                    "错误码完整说明：平台错误分为客户端错误与服务端错误两类。"
                            + "400 表示请求参数错误，常见于 URI 格式不合法、level 取值越界、请求体缺失必填字段，"
                            + "响应 message 会给出具体字段提示。403 表示 scope 越权，请求的 uri 归属域与调用方凭证不匹配，"
                            + "常见于跨用户访问 memories 目录。404 表示节点不存在，uri 未注册或已被删除，先调用 ls 确认路径再重试。"
                            + "409 表示并发冲突，同一节点被同时修改。500 表示服务端内部异常，通常伴随 traceId，"
                            + "可调用 traces 回放接口定位链路。所有错误响应均包含 traceId 字段用于问题追踪。");
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
