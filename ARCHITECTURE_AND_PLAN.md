# ContextFS 架构设计与分阶段开发计划

> 依据 `README.md` 设计。技术栈：JDK 21 + Spring Boot 3.5.x + LangChain4j 1.x + PostgreSQL + Milvus + Redis + OpenTelemetry + Docker。
>
> **Maven 约定**：所有构建/测试/运行命令一律使用本地 Maven（`mvn`），不使用 `mvnw` wrapper。

---

## 1. 总体架构

### 1.1 架构分层

```text
┌─────────────────────────────────────────────────────────┐
│  Agent / Client                                          │
└──────────────────────┬──────────────────────────────────┘
                       │ REST (JSON)
┌──────────────────────▼──────────────────────────────────┐
│  contextfs-api        REST Controller / DTO / 全局异常    │
├─────────────────────────────────────────────────────────┤
│  应用服务层（按域拆分）                                     │
│  ├── FileSystemService   ls/tree/read/write/find          │
│  ├── RetrievalService    search/find 语义检索 + 递归下钻    │
│  ├── SessionService      会话提交与归档                     │
│  ├── MemoryPipeline      记忆提取(异步)：偏好/经验/事实      │
│  └── LevelGenerator      L0/L1/L2 摘要生成（LangChain4j）  │
├─────────────────────────────────────────────────────────┤
│  contextfs-core       域模型 + 端口接口（纯 Java，无框架）  │
│    NodeModel / UriParser / Level / Port 接口               │
├──────────────┬──────────────┬───────────────┬───────────┤
│ contextfs-   │ contextfs-   │ contextfs-    │ contextfs-│
│ storage      │ storage      │ storage       │ observa-  │
│ (PostgreSQL) │ (Milvus)     │ (Redis)       │ bility    │
│ 节点树/日志   │ 向量检索      │ 热点缓存       │ OTel/日志 │
└──────────────┴──────────────┴───────────────┴───────────┘
          全部装配在 contextfs-bootstrap（Spring Boot 启动模块）
```

### 1.2 Maven 多模块与依赖关系

```text
contextfs (parent pom)
├── contextfs-core            # 领域模型、URI 解析、端口接口。零外部依赖
├── contextfs-storage         # core 端口的适配器：PostgreSQL / Milvus / Redis
├── contextfs-retrieval       # 检索引擎（依赖 core、storage）
├── contextfs-memory          # 记忆管线（依赖 core、storage、retrieval）
├── contextfs-observability   # OTel trace、retrieval_log、tool_call_log（依赖 core）
├── contextfs-api             # REST 层（依赖 core、retrieval、memory、observability）
└── contextfs-bootstrap       # Spring Boot 启动、装配、配置（依赖全部）
```

依赖方向（只允许向下依赖，禁止循环）：

```text
bootstrap → api → memory → retrieval → storage → core
                     └────────────────► observability → core
```

设计原则：

1. **端口-适配器**：`core` 定义 `NodeRepository`、`VectorStore`、`CacheStore`、`LlmClient` 等接口，`storage` 提供实现，业务层只面向接口，便于替换存储后端。
2. **core 纯净**：不引入 Spring/SDK 依赖，保证领域模型可单测、可复用。
3. **API 层薄**：只做参数校验、DTO 转换、异常映射，不含业务逻辑。
4. **异步隔离**：记忆管线通过 Spring 事件 / 线程池异步执行，不阻塞会话提交。

### 1.3 核心域模型（contextfs-core）

```java
// 节点
ContextNode {
    String uri;            // ctxfs://{scope}/{path}
    NodeType type;         // DIRECTORY | DOCUMENT | MEMORY | SKILL
    Scope scope;           // global | user/{id} | agent/{id} | session/{id}
    String parentUri;
    Map<String, String> metadata;
    Instant createdAt, updatedAt;
}

// 三层内容
NodeLevel { Level level; String content; int tokenCount; EmbeddingVector vector; }
enum Level { L0, L1, L2 }

// 端口接口
interface NodeRepository    { save/find/ls/tree/delete }        // PostgreSQL
interface VectorStore       { upsert/searchTopK }               // Milvus
interface CacheStore        { get/put/evict, TTL }              // Redis
interface LlmClient         { summarize(text, targetLevel) }    // LangChain4j
interface TraceRecorder     { recordRetrieval/recordToolCall }  // Observability
```

### 1.4 关键流程

**三层加载**（写入时生成，读取时按需）：

```text
write(node) → LlmClient 生成 L0(~100 token)/L1(~2k token)，L2=原文
            → NodeRepository 持久化 + VectorStore 写入三层向量
read(uri, level) → CacheStore 命中? 返回 : Repository 加载 → 回填缓存
```

**目录递归检索**（search 模式）：

```text
query → 向量化 → Milvus topK 目录级(L0) 匹配 → 高分目录入栈
      → 逐目录读取 L0 判断相关性（不相关剪枝）
      → 相关目录加载 L1 概览 → 需要细节时加载 L2
      → 按分数排序 + token 预算截断 → 返回 items + retrieval_log
```

**Session 记忆管线**（异步）：

```text
POST /sessions/{id}/commit → 归档会话为 DOCUMENT 节点
  → 事件异步触发：LLM 按策略提取 preference / experience / fact
  → 与 scope 内已有 MEMORY 节点相似度比较
  → 阈值以上：合并更新；重复：跳过；新知识：新建 MEMORY 节点
```

### 1.5 存储设计

| 存储 | 内容 | 要点 |
|---|---|---|
| PostgreSQL | `ctx_node`（节点树）、`ctx_level`（L0/L1/L2 内容）、`session_record`、`retrieval_log`、`tool_call_log`、`trace_span` | URI 唯一索引 + `(scope, path)` 复合索引；parent_uri 支撑 tree 查询；Flyway 管理 schema |
| Milvus | `ctxfs_chunks` 集合：uri、level、content、embedding | HNSW / IVF 索引，按 scope 分区（partition）实现隔离 |
| Redis | 热点 L0/L1 摘要、检索结果、tree 片段 | key = `ctxfs:{uri}:{level}`，TTL 可配置，写路径主动失效 |

---

## 2. 分阶段开发计划

共 8 个阶段，每个阶段以「可验证产出」收尾，全部测试通过后进入下一阶段。命令均使用本地 `mvn`。

### 阶段 0：工程骨架初始化

**目标**：搭好多模块 Maven 骨架与本地依赖环境。

- [ ] 父 POM（JDK 21、Spring Boot 3.5.x BOM、LangChain4j BOM、公共依赖管理、spotless 插件）
- [ ] 创建 7 个子模块，配置模块间依赖与空包结构
- [ ] `docker/docker-compose.yml`：PostgreSQL 15 / Redis 7 / Milvus standalone / etcd / MinIO
- [ ] `.env.example`、`.gitignore` 校验（敏感配置不入库，仅提交公共配置）
- [ ] bootstrap 空应用可启动，`/actuator/health` 返回 UP

**产出**：可编译可启动的空工程。
**验收**：

```bash
mvn clean install
docker compose -f docker/docker-compose.yml up -d
mvn spring-boot:run -pl contextfs-bootstrap
curl http://localhost:8080/actuator/health
```

### 阶段 1：核心域模型与 URI 解析（contextfs-core）

**目标**：实现纯 Java 领域层，奠定后续所有模块的基础。

- [ ] `ctxfs://{scope}/{path}` URI 解析器：scope 校验（global/user/agent/session）、路径规范化（`..`、`//` 防御）
- [ ] `ContextNode`、`NodeType`、`Level`、`NodeLevel` 等模型
- [ ] 端口接口：`NodeRepository` / `VectorStore` / `CacheStore` / `LlmClient`
- [ ] 内存版 `InMemoryNodeRepository`（供单测与后续集成测试复用）
- [ ] 领域异常体系：`InvalidUriException`、`NodeNotFoundException`、`ScopeViolationException`

**产出**：core 模块 + 高覆盖单测（不依赖 Spring）。
**验收**：`mvn test -pl contextfs-core`

### 阶段 2：存储层适配（contextfs-storage）

**目标**：用真实存储实现 core 的端口接口。

- [ ] Flyway + `V1__init.sql`：`ctx_node`、`ctx_level`、`session_record` 及索引
- [ ] PostgreSQL 实现 `PgNodeRepository`（JDBC/JPA，tree 查询用递归 CTE）
- [ ] Redis 实现 `RedisCacheStore`（TTL、写路径失效）
- [ ] Milvus 实现 `MilvusVectorStore`（建集合、分区、upsert、topK 检索）
- [ ] Testcontainers 集成测试（`integration` profile）

**产出**：storage 模块 + 初始化 SQL + docker/init.sql。
**验收**：`mvn test -pl contextfs-storage`、`mvn verify -Pintegration`

### 阶段 3：VFS 基础操作与 REST API（api + bootstrap）

**目标**：跑通 README 中的文件系统接口，形成第一个可用版本。

- [ ] `FileSystemService`：`ls` / `tree` / `read` / `write` / `find`（此时 read 先只返回 L2/L0 占位）
- [ ] REST Controller：`/api/v1/fs/*`，DTO、参数校验、全局异常处理、统一响应体
- [ ] Redis 缓存接入读路径；写路径失效缓存
- [ ] scope 隔离校验（跨 scope 读写拒绝）
- [ ] MockMvc + Testcontainers API 集成测试

**产出**：fs 全套 API 可用，与 README 的 curl 示例一致。
**验收**：`mvn test`、按 README curl 示例手工验证 write/read/ls/tree/find

### 阶段 4：三层内容生成（L0/L1/L2）

**目标**：接入 LLM，实现层级摘要生成与按需加载。

- [ ] `LlmClient` 端口 + LangChain4j 实现（OpenAI 兼容接口，模型可配置）
- [ ] `LevelGenerator`：write 时异步生成 L0（~100 token）/ L1（~2k token），L2 存原文
- [ ] 目录节点聚合摘要：由子节点 L0 归纳生成目录 L0/L1
- [ ] `read?level=` 按层读取 + Redis 缓存 + token 计数
- [ ] 嵌入模型接入：三层内容向量化写入 Milvus
- [ ] 失败降级策略：LLM 不可用时 L2 直读兜底，任务可重试

**产出**：写入即生成三层，读取按需。
**验收**：`mvn test`、手工验证 `read?level=L0/L1/L2` 返回不同粒度内容

### 阶段 5：目录递归检索引擎（contextfs-retrieval）

**目标**：实现「目录定位 + 逐层下钻」的核心检索能力。

- [ ] `RetrievalService`：search 模式（语义检索 + 递归下钻）与 find 模式（URI/元数据精确匹配）
- [ ] 两阶段检索：Milvus 目录 L0 向量 topK → 相关性判断 → L1 下钻 → 按需 L2
- [ ] 剪枝策略（分数阈值、深度上限）、token 预算控制、结果排序
- [ ] 检索结果记录：命中 URI、层级、分数、耗时
- [ ] `/api/v1/retrieval/search` 接口，响应结构与 README 示例一致
- [ ] 检索质量评测集：构造样例语料，验证召回与层级命中

**产出**：检索引擎 + 评测脚本（记录召回率、平均加载层级、token 消耗）。
**验收**：`mvn test`、评测集跑通并记录基线指标

### 阶段 6：Session 记忆管线（contextfs-memory）

**目标**：实现会话提交后的异步记忆提取与合并。

- [ ] `/api/v1/sessions/{id}/commit`：会话消息落库归档为 DOCUMENT 节点
- [ ] 异步管线（Spring 事件 + 线程池）：归档 → 三类策略提取（preference / experience / fact）
- [ ] 记忆合并决策：与已有 MEMORY 节点相似度比较 → 新建 / 合并 / 跳过
- [ ] 记忆隔离：全局 / `user/{id}` / `agent/{id}` / `session/{id}` 四级 scope
- [ ] 管线可观测：提取、合并决策均落 `retrieval_log` / `tool_call_log`
- [ ] 开关与异步配置（`contextfs.memory.enabled/async`）

**产出**：记忆管线端到端可用。
**验收**：`mvn test`、按 README 提交 session 示例，确认生成 MEMORY 节点且检索可命中

### 阶段 7：全链路可观测（contextfs-observability）

**目标**：traceId 贯穿全链路，支持 bad case 回放。

- [ ] OpenTelemetry 接入：OTLP 导出、MDC 注入 traceId/spanId、REST 入口自动创建 span
- [ ] `retrieval_log` / `tool_call_log` / `trace_span` 记录：query、retrievedUris、level、latencyMs、token、status
- [ ] `/api/v1/traces/{traceId}` 链路查询 API（README 示例）
- [ ] token 消耗统计口径统一（prompt/completion），检索延迟打点

**产出**：可按 traceId 回放任意一次检索的完整决策链。
**验收**：`mvn test`、一次 search 后通过 traces API 完整回放

### 阶段 8：集成打磨与量化评测

**目标**：全量回归、性能与 token 收益量化、文档定稿。

- [ ] 全量测试：`mvn clean verify`（单测 + 集成测试全绿）
- [ ] 量化评测（可用于简历的成果）：
  - 三层加载 vs 直接注入 L2 的 token 消耗对比（目标：显著下降，记录百分比）
  - 检索 P50/P95 延迟、召回率
  - 记忆管线提取准确率（人工抽评）
- [ ] `docs/architecture.md` / `retrieval.md` / `memory.md` 落地
- [ ] 权限模型与多租户隔离（Roadmap 项，视进度取舍）
- [ ] 打包验证：`mvn clean package -DskipTests` + `java -jar` 部署流程

**产出**：可发布版本 + 量化指标报告。
**验收**：`mvn clean verify` 全绿，指标报告完成

---

## 3. 阶段总览

| 阶段 | 内容 | 关键产出 | 核心验收命令 |
|---|---|---|---|
| 0 | 工程骨架 | 多模块 POM + docker-compose | `mvn clean install` |
| 1 | 域模型 | core 模块 + 端口接口 | `mvn test -pl contextfs-core` |
| 2 | 存储层 | PG/Redis/Milvus 适配 | `mvn verify -Pintegration` |
| 3 | VFS API | fs 五操作可用 | `mvn test` |
| 4 | 三层生成 | L0/L1/L2 按需加载 | `mvn test` |
| 5 | 检索引擎 | find/search 递归检索 | `mvn test` + 评测基线 |
| 6 | 记忆管线 | session 提交→记忆生成 | `mvn test` |
| 7 | 可观测 | traceId 回放 | `mvn test` |
| 8 | 打磨评测 | 量化指标 + 文档 | `mvn clean verify` |

依赖关系：0 → 1 → 2 → 3 →（4 → 5 → 6 可适度并行，7 可与 5/6 并行）→ 8。

## 4. 常用命令（本地 Maven）

```bash
mvn clean install                        # 全量构建
mvn test                                 # 全部单元测试
mvn test -pl contextfs-core              # 单模块测试
mvn verify -Pintegration                 # 集成测试（Testcontainers）
mvn spring-boot:run -pl contextfs-bootstrap  # 启动应用
mvn clean package -DskipTests            # 打包
mvn spotless:apply                       # 代码格式化
```

> 注意：Windows 环境下运行 curl 验证时使用 PowerShell 的 `Invoke-RestMethod` 或 Git Bash。
