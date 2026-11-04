# AI Context

`ateng-boot-ai` 负责提供面向生产就绪的 Spring Boot 3 工业级智能体与认知图基础设施 Starter，依托万能 OpenAI-Compatible 协议收敛主流大语言模型，提供多模型命名注册表与线程级动态切源、发票凭单视觉多模态分析、DeepSeek-R1 `<think>` 思考链提炼与 SSE 双通道打字机流式推流、声明式强类型智能体与受管工具沙箱、双模分布式会话记忆全生命周期治理、LangGraph4j 认知状态图编排与人机协作（HITL）断点恢复。

## Language

**AiTemplate**:
大模型调用核心统一门面具体类，提供命令式极简同步对话（`chat`）、视觉多模态分析、安全模板插值、多轮消息对话与 SSE 打字机流式推流（`stream`），自动挂载指数退避重试与四维上下文透传。
_Avoid_: AiUtil, LlmHelper, ChatService, OpenAiClient

**AiModelRegistry**:
多模型命名注册表具体类，统管默认模型与多个命名模型实例，提供运行期模型动态查找、注册与枚举探测能力。
_Avoid_: ModelManager, ModelFactory, ModelPool

**AiContextHolder**:
线程级模型动态切源持有者工具类，基于 ThreadLocal 提供 `useModel` 作用域语法与安全的运行期模型路由切换。
_Avoid_: ModelContext, AiScopeHelper, CurrentModelHolder

**AiAgent**:
声明式智能体代理契约注解，标注于业务接口上，由框架在容器启动期通过动态代理封装 LangChain4j `AiServices`，支撑强类型结构化输出、受管工具调用与会话上下文绑定。
_Avoid_: Agent, AiService, LlmAgent, ChatAgent

**AiAgentScan**:
智能体包扫描引导注解，支持显式声明待扫描并注册为 Spring Bean 的 `@AiAgent` 接口所在基础包路径。
_Avoid_: EnableAgents, ScanAiServices, AgentScanner

**ToolExecutionSandbox**:
受管工具沙箱环绕切面与代理，负责隔离大模型函数调用（Tool Calling）过程，拦截业务语义异常并转译为友好提示回填模型驱动反思自愈，同时对系统级严重异常执行快速熔断。
_Avoid_: ToolInterceptor, ToolProxy, SafeToolExecutor

**ChatMemoryManager**:
对称会话记忆全生命周期治理门面具体类，对外暴露消息历史查询、追加、轮次修剪、清空与存在性探测等完备对称运维接口。
_Avoid_: MemoryHelper, ConversationService, SessionManager

**ChatMemoryStore**:
会话记忆持久化存储 SPI 顶层接口，规范底层消息历史的读取、更新与删除生命周期契约。
_Avoid_: MemoryStorage, ConversationRepository, ChatStore

**InMemoryChatMemoryStore**:
单机内存会话记忆存储实现类，基于并发安全容器管理多轮对话上下文，用于单机微服务部署或离线自洽测试沙箱。
_Avoid_: LocalChatMemory, SimpleMemoryStore

**RedisChatMemoryStore**:
分布式集群会话记忆存储实现类，基于 `ateng-boot-redis` 与 Core 纯净 `JsonUtil` 序列化实现多 Pod 会话漫游与基于 TTL 的自动过期淘汰。
_Avoid_: DistributedChatMemory, RedisSessionStore

**JsonPayloadSanitizer**:
结构化输出防幻觉去噪纯函数工具类，负责剥除大模型输出中携带的 Markdown 代码块语法糖（````json ... ````）及首尾格式噪音，保障 Jackson 强类型反序列化 100% 稳定。
_Avoid_: JsonCleaner, JsonParserUtil, FormatFixer

**ContentRetriever**:
零臃肿外部知识库检索挂载点 SPI 接口，直接对齐 LangChain4j 标准协议，解耦具体向量库驱动，保障底座零沉重依赖。
_Avoid_: KnowledgeRetriever, VectorSearcher, RagService

**ReasonerTokenExtractor**:
思考链提炼状态机纯函数类，基于三状态有限状态机精准剥离 DeepSeek-R1 等推理模型的 `<think>` 思考过程与正文内容，杜绝断字残片外泄。
_Avoid_: ThinkParser, ReasoningFilter, TokenSpliterator

**SseTokenStreamBridge**:
SSE 双通道打字机流式推流桥接器，承接大模型底层流式回调，向客户端异步推送 `event: reasoning` 与 `event: text` 双规数据流并保障四维上下文与链路追踪闭环。
_Avoid_: SseBridge, StreamEmitter, TokenDispatcher

**GraphWorkflow**:
认知状态图工作流抽象基类，封装 LangGraph4j `StateGraph` 的拓扑构建、生命周期编译与多模型驱动。
_Avoid_: WorkflowEngine, GraphBuilder, AiPipeline

**GraphWorkflowRegistry**:
认知状态图工作流注册表具体类，统管 Spring 容器中所有 `GraphWorkflow` 实例，提供基于名称与状态类型的动态检索与路由调度。
_Avoid_: WorkflowManager, GraphPool, WorkflowProvider

**WorkflowStatus**:
认知状态图生命周期状态枚举，严格定义初始运行（`RUNNING`）、断点挂起（`SUSPENDED`）、恢复运行（`RESUMED`）、终态成功（`COMPLETED`）、异常失败（`FAILED`）与主动废弃（`CANCELLED`）原子状态流转。
_Avoid_: StateEnum, GraphStatus, TaskStatus

**BaseGraphState**:
强类型认知状态图状态契约抽象基类，继承 `AgentState` 并通过 `TypedKey<T>` 提供强类型数据访问与纯净 JSON 序列化。
_Avoid_: GraphData, WorkflowContext, StatePayload

**MemoryCheckpointSaver**:
单机内存认知图检查点存储实现类，基于并发安全容器管理检查点，用于轻量开发与 100% 离线自洽单测。
_Avoid_: LocalCheckpointSaver, SimpleCheckpointStore

**RedisCheckpointSaver**:
分布式集群认知图检查点存储实现类，基于 `ateng-boot-redis` 与 Core 纯净 `JsonUtil` 序列化实现多 Pod 状态漫游与基于 TTL 的自动淘汰。
_Avoid_: DistributedCheckpointSaver, RedisSnapshotStore

**GraphResumeTemplate**:
认知状态图人机协作（HITL）恢复门面具体类，提供基于分布式锁与 CAS 状态机防伪的快照查询、审批恢复与流程取消对称接口。
_Avoid_: WorkflowManager, HitlExecutor, ResumeService

**StateSnapshotVo**:
状态图执行快照传输视图对象，封装线程标识、工作流名称、执行状态、当前/下一节点、业务状态纯净视图与中断审批元数据。
_Avoid_: SnapshotDto, GraphStatusResponse, WorkflowSnapshot

**AtengAiObservabilityListener**:
基于 LangChain4j ChatModelListener SPI 的全维可观测审计监听实现类，统一采集请求耗时、四维上下文透传与 Token 计数并挂载 Micrometer 云原生度量指标。
_Avoid_: AiTracingInterceptor, LlmMetricsAspect, ModelAuditFilter

**FallbackTokenEstimator**:
流式打字机 Token 启发式保底估算纯函数工具类，在远程网关未返回 Usage 时基于中英加权算法推算 Token 消耗并标记 estimated=true 消除监控空洞。
_Avoid_: TokenCalculator, UsageGuesser, TokenHeuristic

**AiQuotaChecker**:
多租户与用户级大模型调用额度风控拦截器 SPI 顶层接口，负责在远程模型调用发起前执行调用次数与 Token 配额校验，超额即刻 Fail-Fast 阻断以防巨额账单冲击。
_Avoid_: QuotaService, RateLimiter, TokenGatekeeper

**AiTenantModelResolver**:
多租户动态模型切源与凭据解析 SPI 顶层接口，支持根据租户上下文动态路由至专有大模型实例或隔离配置。
_Avoid_: TenantModelRouter, TenantModelFactory, ModelTenantMapping

**AiAuditRecord**:
结构化可观测审计记录契约模型对象，封装请求耗时、模型标识、四维上下文快照（MDC traceId、租户、用户）、Token 消耗明细与脱敏后出入参。
_Avoid_: AuditEntry, AiLogItem, TelemetryEvent

**AiAuditLogHandler**:
可观测审计记录外部持久化扩展点 SPI 接口，支持企业无侵入对接 Kafka、Elasticsearch 或数据库落盘，默认受管异步安全调度防链路击穿。
_Avoid_: AuditListener, LogConsumer, TelemetrySink

**DefaultAiQuotaChecker**:
多租户大模型调用配额风控默认实现类，自适应双模计数（无 Redis 单机并发内存淘汰，有 Redis 分布式天级滑动计数与 TTL 自动失效）。
_Avoid_: SimpleQuotaChecker, StandardQuotaService, MemoryQuotaManager

**EmbeddingTemplate**:
统一命令式向量化计算与知识库切片入库门面具体类，提供对称的单文本、批量文本、`TextSegment` 稠密向量计算与 `ingest` 一键入库，自动挂载指数退避重试并平滑处理网络瞬态异常。
_Avoid_: EmbeddingHelper, VectorUtil, EmbeddingService

**InMemoryEmbeddingStore**:
单机内存向量库存储实现类，基于余弦相似度计算与并发安全集合管理向量索引，作为无外部向量中间件时的自动降级兜底方案，确保 100% 离线自洽单测与轻量单机启动。
_Avoid_: LocalVectorStore, SimpleEmbeddingStore, MemoryStore

**AiGuardrail**:
企业级 AI 双向内容安全护栏顶层 SPI 接口，定义前置提示词注入/越狱检测（`inspectPrompt`）与后置生成内容敏感词审查（`inspectResponse`）的双向安全防卫契约。
_Avoid_: ContentFilter, PromptInspector, SecurityInterceptor

**DefaultAiGuardrail**:
基于高性能 Trie 前缀树与正则匹配的双向内容安全护栏默认实现类，内置越狱攻击特征库与免检白名单，支持 `FAIL_FAST` 熔断与 `REPLACE` 友好降级双模式。
_Avoid_: SimpleGuardrail, StandardContentFilter

**ActiveStreamRegistry**:
活动流式连接物理注册中心具体类，基于 OkHttp 拦截器与弱引用安全追踪活跃的网络长连接 `Call`，支持在 SSE 客户端中断时主动执行物理取消（`call.cancel()`）以杜绝 Token 计费泄漏。
_Avoid_: StreamManager, CallRegistry, ActiveConnectionPool

**StreamCancellationInterceptor**:
OkHttp 物理流式调用生命周期拦截器具体类，在发起网络请求时自动向 `ActiveStreamRegistry` 注册当前 `Call`，在请求正常完成或异常关闭时确定性注销。
_Avoid_: CancelFilter, StreamInterceptor

**TokenWindowChatMemory**:
基于 Token 数量的会话记忆滑动裁剪窗口具体类，自动保留首条系统指令（`SystemMessage`）并在历史超出最大 Token 限制时由远及近淘汰旧消息，防止模型上下文超长击穿。
_Avoid_: TokenMemory, TokenSlider, SlidingWindowMemory
