# ADR-0111: AI 双级语义缓存、动态提示词托管、并行认知图、BM25 多租户倒排检索、按需 RAG 评测与零扣费健康探针架构

- **状态 (Status)**: `Accepted`
- **日期 (Date)**: `2026-11-22`
- **关联模块 (Modules)**: `ateng-boot-ai`、`ateng-boot-observability`、`ateng-boot-core`、`ateng-boot-redis`、`ateng-boot-samples/ateng-boot-sample-ai`
- **演进溯源 (Extends / Consolidates)**: 继承并固化 `#1158`~`#1162` 跨五个切片的架构演进决策，补充修订 [ADR-0088](./0088-enterprise-ai-observability-quota-governance-and-production-architecture.md)、[ADR-0090](./0090-enterprise-ai-rag-guardrail-and-streaming-cancellation-architecture.md) 与 [ADR-0091](./0091-enterprise-ai-agent-self-reflection-hitl-tool-approval-and-episodic-memory-architecture.md) 相关生产默认契约。

---

## 1. 背景与痛点 (Context)

`ateng-boot-ai` 历经前期多轮基础设施与认知状态图建设后，在投入大规模高并发、多租户 SaaS 与企业生产运维前仍存在以下关键缺口：
1. **重复/相似问答成本高与热路径误缓存风险**：缺少精确 Hash 与向量语义双级响应缓存体系；若朴素缓存不区分 `tenantId`、系统提示词摘要、采样温度、工具声明及 PII 敏感文本，极易造成跨租户串话或隐私泄漏。
2. **动态提示词缺乏集中托管与严格变量防卫**：早期 `PromptTemplate` 仅支持静态加载，缺少按编码/版本号/环境标签（`prod`/`canary`）寻址与运行期受控热刷新，且缺失占位符时可能将 `{{var}}` 原文直接泄露给大模型；同时结构化输出与外部工具接入需强化网关原生 `strict-native-json-schema` 与 OpenAPI SSRF 私网防护。
3. **可观测维度爆炸与假死/误扣费探针缺口**：高基数租户 ID 直接打入 Micrometer `tenant_id` 标签会击穿 Prometheus TSDB；`AiQuotaChecker` 等并发设施存在虚拟线程 Pinning/计数漂移风险；`ateng-boot-observability` 缺少对 `ateng-boot-ai` 服务的零扣费安全健康探针。
4. **并发分支图缺失与单机 BM25 状态割裂**：仅支持串行与辩论工作流，缺少四维上下文透传与隔离状态的并行扇出认知图；`Bm25KeywordRetriever` 在自动装配期注入静态空集合导致混合检索稀疏路失效，且每次全量分词、未下推租户过滤；缺少离线按需量化评测 RAG 检索生成质量的官方门面。

---

## 2. 决策内容与落地设计 (Decision)

### 2.1 双级响应缓存与严格防串话作用域 (`AiSemanticCache` / `AiCacheStore`)
- 提供精确复合 Hash 一级缓存 + 向量余弦相似度语义二级缓存，默认关闭（`ateng.boot.ai.cache.enabled=false`），支持按需显式开启；
- 缓存作用域强绑定 `tenantId + modelName + samplingParams + systemMessageDigest`，与主 RAG 知识库 `EmbeddingStore` 100% 物理隔离；自动豁免流式推流、携带 `ToolSpecification`、温度 `>= max-cacheable-temperature` 与 PII 检出文本（`cache-pii=false`），并提供 `AiContextHolder.bypassCache()` 线程级请求穿透；
- 通过 `AiCacheStore` SPI 实现单机有界 LRU（`InMemoryAiCacheStore`，`ReentrantLock` 防 Pinning）与分布式 Redis（`RedisAiCacheStore`，强类型 `Duration` TTL）自适应双模切换，上报低基数指标 `ateng.ai.cache.requests.total`。

### 2.2 动态提示词托管中心与安全加固体系 (`PromptRegistry` / SSRF / MCP 只读契约)
- 新增 `PromptRegistry`、`PromptProvider` SPI（内置本地 YAML/MD Classpath 实现，支持外部 Nacos/DB 接管）与不可变 `PromptDefinition`，支持按编码、版本号与标签路由、`renderStrict`（缺失必填变量立即抛 `AI_INVALID_PAYLOAD`）、`AiTemplate#chatByPromptCode` / `streamByPromptCode` 及 `@AiAgent(promptCode=...)`；
- 新增 `AiModelConfig#strictNativeJsonSchema`（配合 `NativeJsonSchemaContext`）启用原生网关严格 Schema 解码；为 `OpenApiToolProperties` 增加 `blockPrivateNetwork` 与 `allowedHosts` SSRF 防线；扩展 `McpClientAdapter` 支持 MCP 只读 `listResources` / `readResource` / `listPrompts` / `getPrompt`。

### 2.3 低基数可观测收敛与零扣费 AI 安全健康探针 (`AiSafeHealthIndicator`)
- 确立 `AiAuditProperties` 生产默认值与配置结构：`log-payload=false`、`max-payload-chars=2000`、`aggregate-tenant-metrics=true`（非空租户统一折叠为 `"tenant"` 防高基数爆表）、财务定价收敛于 `audit.default-pricing` / `audit.pricings.<name>.(input|output)-cost-per-million` 并输出累加指标 `ateng.ai.cost.total`（6 位小数 `HALF_UP`）；全量并发锁采用 `ReentrantLock` / CAS 兼容虚拟线程运行时；
- 在 `ateng-boot-observability` 新增 `AiSafeHealthIndicator`（`ateng.boot.observability.health.ai.*`）：默认仅校验静态预热模型配置完整性（无密钥安全返回 `UNKNOWN`），开启 `probe-models=true` 仅发起零 Token 计费的只读 `GET /models`，内置 `60s` 防刷缓存并强制 `******` 脱敏错误明细中的 API Key 与凭据。

### 2.4 并行扇出认知图、BM25 增量倒排检索与按需 RAG 评测
- **`ParallelFanOutWorkflow<S>`**：扩展 `GraphWorkflow<S>` 构建 `fanout -> aggregate` 拓扑；接入 Core 受管 `TaskExecutor`（默认回退 `AsyncUtil.getExecutor()`）并用 `AsyncContext.wrap` 透传四维上下文；每个并发分支分配独立隔离的 `stateFactory` 状态快照副本；支持单分支 `Duration` 纳秒级硬超时、`FAIL_FAST`（取消未完成分支并沿因果链抛出保留原始 `cause` 的 `AiException`）与 `BEST_EFFORT`（结构化错误写入 `ParallelState.BRANCH_ERRORS`）。
- **`Bm25KeywordRetriever` & `RagIngestionTemplate` 联动**：在 100% 保留原有 4 个构造器基础上升级为 `ReentrantLock` 守护的增量倒排索引（`addSegments`/`removeByDocId`/`rebuild`/`clear`/`size`），由 `RagIngestionTemplate` 在 `ingest`/`OVERWRITE`/`deleteDocument` 成功后自动同步，并下推 `TenantContextHolder` 与 `DynamicFilterContextHolder` 过滤；同时修复 `ReciprocalRankFuser` 读取整型 `chunk_index` 元数据兼容性缺陷。
- **显式按需 `RagEvaluator`**：仅在显式调用 `evaluate(RagEvaluationRequest)` 时通过 `AiTemplate.chatForObject` 产出 `faithfulness`、`answerRelevance`、`contextRecall`、`overallScore`（归一化 `[0.0, 1.0]`）与 `reasoning`，严禁挂载至日常 `AiTemplate.chat` 在线热路径。

---

## 3. 后果与权衡 (Consequences & Trade-offs)

- **单 JVM BM25 边界与集群演进路径**：内置 `Bm25KeywordRetriever` 面向单进程轻量与离线自洽场景；跨 Pod 微服务集群如需共享分布式稀疏检索状态，业务可通过替换 `ContentRetriever` SPI Bean 对接 `ateng-boot-search` 或 `ateng-boot-milvus` 稀疏向量检索。
- **缓存一致性与低基数权衡**：语义缓存默认关闭并采用租户+参数+系统提示词复合作用域与温度阈值守卫，以宁可保守不命中为代价杜绝幻觉串话与敏感隐私残留；`aggregate-tenant-metrics=true` 折叠高基数租户标签保护了 Prometheus 存储稳定性，单租户下钻需显式切换开关为 `false`。
- **Sample-First 1:1 双向验真闭环**：所有新增 API 均已通过 `ateng-boot-ai`、`ateng-boot-observability` 与 `ateng-boot-sample-ai`（MockMvc / `@SpringBootTest`）全量自动化验证并 1:1 逆向沉淀至各模块 5D `README.md` 与四段式体验卡。
