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
