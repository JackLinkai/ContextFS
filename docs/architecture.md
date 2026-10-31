# 架构设计

本文描述 ContextFS 的整体架构、模块划分、端口-适配器边界、可观测性设计与部署形态。功能示例见 [README](../README.md)，检索细节见 [retrieval.md](retrieval.md)，记忆管线见 [memory.md](memory.md)。

---

## 1. 设计目标

- **统一寻址**：所有上下文（记忆 / 资源 / 技能 / 会话归档）挂载在 `ctxfs://{scope}/{path}` 虚拟文件系统下，用文件系统语义组织，而非扁平向量集合。
- **按需加载**：L0 摘要 / L1 概览 / L2 原文三层内容，检索时先匹配摘要再逐层下钻，降低注入 token。
- **核心域纯净**：core 模块不依赖任何具体存储、HTTP 框架或 LLM SDK；外部能力全部通过端口（接口）注入。
- **全链路可观测**：一次检索或会话提交可通过 `traceId` 完整回放输入、命中、耗时与 token。

---

## 2. 模块划分

```text
contextfs-core           核心域：URI、节点模型、端口、InMemory 实现、异常、token 估算
contextfs-retrieval      检索引擎：SEARCH / FIND 双模式、目录下钻、三层装配、评测集
contextfs-memory         记忆管线：会话归档、规则提取、相似合并决策、评测集
contextfs-observability  可观测端口：TraceLogStore、三表模型、TraceSpanFilter
contextfs-storage        适配器：PostgreSQL / Milvus / Redis / LLM 客户端 / Flyway 迁移
contextfs-api            REST 层：Controller、DTO、统一响应、异常映射、层级生成器
contextfs-bootstrap      启动装配：Spring Boot 入口、StorageConfig 装配、fat jar
```

依赖方向（自上而下，禁止反向）：

```text
bootstrap ──> api ──> ┌─ retrieval ──┐
                      │              ├─> core
        observability ┘─ memory ─────┘
             ^                ^
             |                |
        storage（实现 core / observability 定义的端口）
```

- **core** 定义端口：`NodeRepository`、`VectorStore`、`EmbeddingClient` 等。
- **observability** 定义 `TraceLogStore` 端口与三表记录模型（`RetrievalLogEntry` / `ToolCallLogEntry` / `TraceSpanEntry`），并提供 InMemory 实现。
- **storage** 提供生产实现：`PgNodeRepository`、`MilvusVectorStore`、`PgTraceLogStore` 等，由 bootstrap 的 `StorageConfig` 以 `@Primary` Bean 装配覆盖默认实现。
- 各业务模块（retrieval / memory）只依赖 core 与 observability 的端口，对 PG / Milvus 无感知。

---

## 3. 核心域模型

| 模型 | 说明 |
|---|---|
| `CtxfsUri` | 不可变值对象，`ctxfs://{scope}/{path}`；scope 限 `global` / `user/{id}` / `agent/{id}` / `session/{id}`；path 规范化（折叠分隔符、拒绝 `.`/`..`、深度上限 8） |
| `ContextNode` | 节点元数据：uri、type、parentUri、metadata、createdAt、updatedAt |
| `NodeType` | `DIRECTORY` / `DOCUMENT` / `MEMORY` / `SKILL` |
| `NodeLevel` | 单层内容：level、content、tokens |
| `Level` | `L0` 摘要 / `L1` 概览 / `L2` 原文 |
| `Tokens` | token 估算：CJK 字符 1 token，其余每 4 字符 1 token（后续可替换为真实分词器） |

---

## 4. 请求生命周期

### 4.1 写入（`POST /api/v1/fs/write`）

```text
FsController.write
  -> 解析 CtxfsUri、保存 ContextNode
  -> LevelGenerator 生成 L0 / L1 / L2（LLM 生成，失败时降级为占位摘要并保留原文）
  -> 每层内容通过 EmbeddingClient 向量化后写入 VectorStore
```

层级生成与向量写入失败不阻塞写入主流程，记录 WARN 并在后续写入时重试。

### 4.2 检索（`POST /api/v1/retrieval/search`）

```text
RetrievalController.search
  -> RetrievalService.retrieve(scope, query, mode, configOverride, traceId)
  -> SEARCH：L0 向量召回 -> 目录递归下钻 -> 三层内容装配 -> 阈值剪枝 -> token 预算截断
  -> FIND：URI / 内容关键词精确匹配
  -> 返回 RetrievalResult（items、tokenUsage、latencyMs）
  -> 异步写入 retrieval_log
```

### 4.3 会话提交（`POST /api/v1/sessions/{sessionId}/commit`）

```text
SessionController.commit
  -> 从 X-ContextFS-Trace-Id 头取 traceId（缺省生成 UUID）
  -> MemoryPipelineService.commit / commitAsync
  -> 会话归档 -> 记忆提取 -> 逐条合并决策 -> 写入 tool_call_log
```

支持同步与异步（`contextfs.memory.async`）两种模式；异步通过线程池执行，traceId 在提交前于 HTTP 线程捕获后随任务传入。

---

## 5. 可观测性设计

### 5.1 traceId 贯穿

```text
客户端请求（携带 X-ContextFS-Trace-Id，可缺省）
  -> TraceSpanFilter：透传或生成 traceId
     -> 写入 request attribute（ctxfs.traceId）与 MDC（traceId / spanId）
     -> 响应头回写 X-ContextFS-Trace-Id
     -> 请求结束后记录 HTTP span（method、path、status、耗时）
  -> 检索 / 会话提交：traceId 随调用链传入服务层
  -> 异步任务：HTTP 线程捕获 traceId 后随 lambda 传入（MDC 不跨线程）
```

- 检索请求体的 `traceId` 字段优先级高于请求头（两者一致时归并为同一链路）。
- `/actuator` 路径不参与 span 记录。
- spanId 为 16 位 hex，同一 traceId 下多次操作可通过 span 区分。

### 5.2 三表记录

| 表 | 记录内容 |
|---|---|
| `retrieval_log` | traceId、sessionId、agentId、scope、query、mode、retrievedUris、levels、latencyMs、promptTokens、status |
| `tool_call_log` | traceId、tool（`memory.extract` / `memory.decide`）、input / output（JSON）、latencyMs、status |
| `trace_span` | traceId、spanId、parentSpanId、operation、attributes、status、startedAt / endedAt |

三者均含 trace_id 索引，由 Flyway `V2__observability.sql` 建表。

### 5.3 链路回放

```text
GET /api/v1/traces/{traceId}
```

聚合返回该 traceId 下的全部 span、检索日志与工具调用日志；三类均无记录时返回 404。可复盘任意一次检索的完整决策链。

### 5.4 关于 OpenTelemetry 的取舍

当前版本采用**业务级 traceId 体系**（Filter + MDC + 三表 + 回放 API）而非引入 micrometer-tracing / OTel SDK：

- 本地开发环境无 collector，OTLP 导出无法端到端验证，不引入未经验证的依赖；
- 三表模型直接面向业务查询（命中了什么、花了多少 token、每步多久），比通用 span 更适合 bad case 复盘；
- `.env.example` 保留 `OTEL_EXPORTER_OTLP_ENDPOINT` 作为预留，后续接入 OTel SDK 时 traceId 语义可无缝迁移。

---

## 6. 错误模型与 API 约定

- 所有响应包裹在 `ApiResponse`：`code=0`（message `ok`）表示成功，非 0 表示业务错误。
- 领域异常继承 `ContextFsException`（core 定义），由 `GlobalExceptionHandler` 统一映射：
  - `NodeNotFoundException` -> 404
  - `InvalidUriException` -> 400
  - `TraceNotFoundException` -> 404
- 错误响应包含 traceId，可直接调用 traces 回放接口定位链路。

---

## 7. 存储与部署

### 7.1 持久化

- **PostgreSQL**：节点树、三层内容、观测三表；Flyway 迁移 `V1__init.sql`（核心表）与 `V2__observability.sql`（观测三表）。
- **Milvus**：L0 / L1 / L2 向量，支持目录定位与内容检索。
- **Redis**：热点目录与摘要缓存。
- **LLM**：层级生成与向量化的外部模型客户端（OpenAI 兼容协议）。

### 7.2 双实现装配

- 单元测试与本地无依赖场景：core / observability 提供的 InMemory 实现 + `HashingEmbeddingClient`（字符 unigram + bigram 哈希），全链路可脱离外部服务运行。
- 生产装配：bootstrap `StorageConfig` 将 PG / Milvus / Redis 实现以 `@Primary` 注册，覆盖 classpath 上的 InMemory 默认实现；`TraceSpanFilter` 同样由 `StorageConfig` 显式装配（避免模块内组件扫描带来的双 Bean 歧义）。

### 7.3 打包与启动

```bash
mvn clean package -DskipTests
java -jar contextfs-bootstrap/target/contextfs.jar
```

启动流程：Spring Boot 装配 -> Flyway 迁移（V1 / V2）-> 服务就绪。未配置 PG 时启动会因数据库初始化失败退出，属预期行为；纯内存验证请运行测试套件或使用 InMemory 装配。

### 7.4 环境变量

见 [README 配置说明](../README.md#配置说明)。关键项：`POSTGRES_URL`、`REDIS_HOST`、`MILVUS_HOST`、`CONTEXTFS_MEMORY_ENABLED`、`CONTEXTFS_MEMORY_ASYNC`、`OTEL_EXPORTER_OTLP_ENDPOINT`（预留）。

---

## 8. 测试策略

| 层次 | 手段 |
|---|---|
| 核心域 | InMemory 端口实现上的纯单测（URI 规范化、节点树、token 估算） |
| 检索引擎 | InMemory + HashingEmbeddingClient 的行为单测（下钻、剪枝、预算、降级、scope 隔离） |
| 记忆管线 | InMemory 全链路单测（归档、提取、合并、可观测写入） |
| REST 层 | MockMvc standalone（envelope、异常映射、trace 头透传） |
| 端到端 | `TraceReplayEndToEndTest`：search -> traces 回放全链路 |
| 量化评测 | `RetrievalEvalTest`、`MemoryExtractionEvalTest`（见 retrieval.md / memory.md） |

全部测试不依赖 Docker、真实 LLM、Milvus 或 PostgreSQL，`mvn clean verify` 即可完整回归。
