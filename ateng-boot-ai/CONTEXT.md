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

**DocumentSplitter**:
原生轻量长文本智能切片门面具体类，提供基于字符滑动窗口、换行段落及 Markdown 标题层级的有界重叠（Overlap）切片算法，直接产出 `TextSegment` 并继承元数据，零引入第三方臃肿解析包。
_Avoid_: TextChunker, DocParser, FileSplitter

**Reranker**:
文档检索精排模型 SPI 顶层接口，定义对向量粗排召回候选集进行交叉打分（Cross-Encoder Scoring）与高精排序的规范契约。
_Avoid_: Ranker, ReRankService, ScoreFilter

**RerankingContentRetriever**:
融合精排的复合知识检索器具体类，封装向量相似度召回与 `Reranker` 交叉重排两阶段检索管道，支持超时熔断与保底降级机制。
_Avoid_: HybridRetriever, AdvancedRetriever, SmartRetriever

**FailoverChatLanguageModel**:
多模型高可用故障转移代理实现类，统管声明式主备模型降级链，具备共享超时时限（Total Deadline）与非业务错误精准熔断转移机制。
_Avoid_: FallbackModel, BackupModel, RouterModel

**McpClientAdapter**:
模型上下文协议（Model Context Protocol）轻量客户端桥接 SPI 接口，支持将符合标准 MCP 规范的外部工具与资源一键注册为当前智能体受管工具。
_Avoid_: McpConnector, McpProxy, McpHelper

**QueryTransformer**:
多轮对话检索查询重写与指代消解 SPI 顶层接口，负责将多轮历史中的模糊代词与上下文改写为语义完整独立的检索 Query，提升知识库精准命中率。
_Avoid_: QueryRewriter, PromptTransformer, SearchReformulator

**AiRateLimiter**:
突发流量速率限流器 SPI 接口，基于滑动窗口提供微观秒级请求频次（RPS）与分钟级 Token 突发（TPM）双重流控卫语句，防御上游服务商 429 封禁风险。
_Avoid_: AiThrottler, RateLimitService, TokenBucketFilter

**SummaryChatMemory**:
长期会话摘要记忆具体类，依托后台受管线程池异步归纳超长历史并生成长期摘要，维持 `[System: 长期摘要] + [近 N 轮原始明细]` 双段式结构，兼顾长期上下文与打字机推流零延迟。
_Avoid_: SummaryMemory, CompactingMemory, AbstractChatMemory

**TokenCostCalculator**:
大模型调用成本与财务账单计算纯函数工具类，基于配置的模型单价表（Input/Output 每百万 Tokens 定价）实时计算单次请求金额并挂载度量指标。
_Avoid_: CostEstimator, PricingCalculator, TokenBillingUtil

**SensitiveTool**:
高危敏感工具声明式契约注解，标注于受管 `@Tool` 方法上，声明风险等级、审批分类与人机协同审批策略。
_Avoid_: DangerousTool, ProtectedTool, ApprovalRequired

**ToolApprovalTemplate**:
高危工具人机协同审批生命周期治理门面具体类，提供挂起工单生成、快照持久化、审批授权、拒绝回填与方法安全重放等对称接口。
_Avoid_: ToolApprovalManager, ToolApprovalService, ApprovalHelper

**SupervisorWorkerWorkflow**:
基于 LangGraph4j 的主管-专工分层认知状态图工作流基类，提供中心主管分解任务并动态调度专工 Agent 协同执行的标准拓扑。
_Avoid_: AgentSupervisor, MultiAgentWorkflow, TeamWorkflow

**EpisodicMemoryTemplate**:
跨会话长期语义情景记忆门面具体类，提供基于大模型的事实知识抽取、用户画像沉淀、向量关联检索与跨会话自动注入。
_Avoid_: LongTermMemoryService, UserPersonaManager, MemoryEngine

**MemoryFact**:
原子化用户长效记忆事实契约对象，封装主体、谓词、客体、事实陈述、置信度、时间戳与多租户/用户所有权元数据。
_Avoid_: FactRecord, MemoryItem, UserFact

**RagResponse**:
强类型可信 RAG 问答响应契约对象，封装生成正文、证据切片精准溯源引用列表与可选事实忠实度评分。
_Avoid_: RagResult, AnswerWithCitation, GroundedAnswer

**Citation**:
RAG 知识溯源引用结构化模型对象，封装引用序号、关联切片 ID、源文档名、页码定位、引用原文片段与语义相关度分数。
_Avoid_: SourceReference, FootnoteItem, ReferenceDoc

**CitationExtractor**:
RAG 溯源引用确定性提取与上下文对齐纯函数工具类，负责将大模型回答中的引用锚点与召回切片元数据精确绑定并输出结构化引用列表。
_Avoid_: ReferenceParser, CitationResolver, FootnoteExtractor

**AgentStreamEvent**:
智能体多通道流式过程事件领域模型契约，封装事件类型（`AgentStreamEventType`）、事件时间戳、节点标识、工具名称、参数、思考链片断、正文分片等全流程生命周期事件载荷。
_Avoid_: StreamPayload, AgentEvent, EventNotification

**AgentStreamListener**:
智能体流式事件监听器 SPI 接口，定义思考链生成、工具执行启动与返回、高危审批拦截、工作流节点流转等生命周期事件的捕获与响应契约。
_Avoid_: StreamConsumer, AgentEventListener, EventCallback

**AgentResumeResult**:
高危工具人机协作审批（HITL）主动续跑契约模型，封装审批工单、是否触发续跑、续跑状态与智能体最终回答结果。
_Avoid_: ResumeResponse, AgentApprovalResult, ToolResumeVo

**OpenApiToolAdapter**:
企业 OpenAPI 3.0 / Swagger 2.0 契约转 Agent 受管沙箱工具适配器具体类，纯原生解析端点契约并转换为 LangChain4j ToolSpecification，支持 4D 治理上下文透传与沙箱自愈。
_Avoid_: SwaggerToolAdapter, ApiToolBridge, OpenApiHelper

**OpenApiToolProperties**:
OpenAPI 工具适配器强类型配置属性类，规范服务基础路径、鉴权头、超时时限与端点过滤白名单。
_Avoid_: ApiConfig, OpenApiConfig, SwaggerProperties

**SequentialPipelineWorkflow**:
基于 LangGraph4j 的确定性多阶段顺序流水线工作流基类，提供声明式阶段编排（`PipelineStage`）、前瞻跳过条件求值（Lookahead skip）、前置静态中断与断点续跑闭环。
_Avoid_: LinearWorkflow, StagePipeline, StepWorkflow

**PipelineStage**:
顺序流水线阶段契约模型，封装阶段名称、阶段描述、阶段处理器（`StageHandler`）、跳过断言（skipCondition）及前置中断配置（interruptBefore）。
_Avoid_: PipelineStep, WorkflowStage, GraphStep

**PipelineState**:
顺序流水线工作流强类型基类，继承 `BaseGraphState`，提供当前阶段、跳过阶段集、完成阶段集与阶段执行结果强类型 TypedKey。
_Avoid_: PipelineContext, StageState, StepContext

**DebateConsensusWorkflow**:
基于 LangGraph4j 的对抗式多智能体评审与共识裁决工作流基类，统管多方轮转辩论（`DebateParticipant`）、共识判定（`checkConsensus`）、仲裁员终局裁决与 `maxDebateTurns` 硬看门狗安全兜底。
_Avoid_: ArgumentWorkflow, MultiAgentDebate, ConsensusEngine

**DebateParticipant**:
对抗式辩论参与者契约模型，封装辩手名称、预设立场、职责描述与辩论动作处理器（`DebateHandler`）。
_Avoid_: Debater, DebateMember, AgentParticipant

**DebateSpeech**:
不可变结构化辩论发言契约模型，封装发言轮次、辩论者标识、立场观点、论述内容与时间戳。
_Avoid_: SpeechRecord, ArgumentItem, DebateUtterance

**DebateState**:
对抗式多智能体辩论强类型状态基类，继承 `BaseGraphState`，提供辩题、轮次计数、历史发言记录集、共识达成标志与仲裁终局裁决 TypedKey。
_Avoid_: DebateContext, ArgumentState, ConsensusState
