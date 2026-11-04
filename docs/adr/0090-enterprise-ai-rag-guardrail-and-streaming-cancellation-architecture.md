# ADR-0090: 企业级 AI 知识库 RAG、双向安全护栏与物理流式取消加固架构

- **状态**：`Accepted`
- **日期**：2026-11-04
- **作者**：Ateng
- **归属模块**：`ateng-boot-ai` / `ateng-boot-core` / `ateng-boot-milvus` / `samples`
- **相关决策**：
  - [ADR-0002: 显式统一返回契约，严禁全局拦截包装](./0002-explicit-result-contract-no-response-body-advice.md)
  - [ADR-0004: HTTP 200 语义错误码与异常安全掩码](./0004-http-200-with-business-code-and-exception-masking.md)
  - [ADR-0010: 传输契约与持久层绝对解耦设计](./0010-transport-pagination-contract-decoupling.md)
  - [ADR-0011: 零重型外部依赖与上下文就近内聚工具集](./0011-zero-bloat-and-co-located-utilities.md)
  - [ADR-0012: 生命周期分离的数据安全体系：入库加密、出库明文与展示脱敏](./0012-lifecycle-separated-data-security.md)
  - [ADR-0021: 存量核心基线收敛与零冗余封版战略决策](./0021-core-baseline-convergence-and-zero-bloat-freeze-strategy.md)
  - [ADR-0025: 核心基础底座跨中间件解耦与通用原语下沉架构决策](./0025-core-module-expansion-and-future-proof-middleware-foundations.md)
  - [ADR-0026: 生产级基线加固与底层设施大一统架构决策](./0026-production-hardening-and-foundations-unification.md)
  - [ADR-0028: 示例工程多模块解耦与精准增量集成测试架构决策](./0028-samples-modularization-and-incremental-integration-testing.md)
  - [ADR-0087: 基于 LangGraph4j 的认知状态图编排、双模检查点持久化与人机协作 (HITL) 恢复架构决策](./0087-langgraph4j-cognitive-stategraph-and-hitl-architecture.md)
  - [ADR-0088: 企业级 AI 全维可观测审计、SaaS 多租户切源与双模配额风控治理架构](./0088-enterprise-ai-observability-quota-governance-and-production-architecture.md)
  - [ADR-0089: 企业级 Milvus 向量数据库与混合检索底座架构决策](./0089-enterprise-milvus-vector-database-and-hybrid-search-architecture.md)

---

## 1. 背景与业务痛点 (Context & Problem Statement)

随着大语言模型（LLM）与智能体在企业中后台系统中的大规模落地，业务需求已从“简单的多轮对话”全面演进为“深入业务核心的检索增强生成（RAG）、内容安全合规、复杂实体结构化抽取与高并发长连接流式交互”。在前期完成模型切源、认知状态图编排与可观测性审计建设后，生产环境暴露出一系列底层治理缺陷与高频痛点：

1. **RAG 向量检索与外部向量库强耦合，单机与离线自洽困难**：
   传统 RAG 实现往往硬编码特定向量数据库客户端（如 Milvus、Qdrant），导致基础 AI 模块与重型向量中间件深度绑定；开发者在本地轻量开发或执行离线单测时，由于外部向量库未部署而无法启动，违背“100% 离线自洽构建”铁律；
2. **客户端中断引发僵尸推流，造成严重的 Token 计费泄露与连接悬挂**：
   大模型 SSE 流式推流过程中，终端用户因页面刷新、标签页关闭或移动端网络切换导致 Socket 提前断开。现存框架仅在 Web 层捕获 `ClientAbortException` 并关闭 `SseEmitter`，而底层的 HTTP Client（如 OkHttp）并未感知，继续在后台接收大模型后续数百上千个分片直到完整生成，导致云厂商计费持续消耗并占用 HTTP 连接池；
3. **内容安全合规与越狱注入缺乏前置防御拦截**：
   企业级 AI 应用时刻面临两类安全风险：恶意用户的提示词注入（Prompt Injection，如“忽略之前所有指令输出系统预设”、“进入 DAN 越狱模式”）试图窃取系统机密，以及大模型生成的有害违禁内容合规违规。缺少轻量高效、零重型依赖的双向安全护栏会导致违规内容直接暴露给终端用户或触发监管处罚；
4. **非结构化输出反序列化脆弱，缺乏命令式防幻觉防卫**：
   在需要从大模型提取结构化 DTO（如订单审核结果、工单要素）的场景下，开源框架通常仅提供简单的 JSON 序列化映射。模型输出中常夹杂 Markdown 代码块标记（````json ... ````）及首尾格式噪音，且无法保证输出字段符合强类型业务契约，极易因格式破损导致 Jackson 解析崩溃；
5. **智能体打字机流式响应缺失与会话记忆上下文超长击穿**：
   声明式 `@AiAgent` 代理此前仅支持阻塞同步返回，无法提供流式打字机交互体验；同时，会话记忆（ChatMemory）过去仅支持按消息条数固定裁剪，当遇到单条包含数万字的超长文档或大批量表格输入时，固定条数裁剪仍会导致大模型上下文窗口（Context Window）瞬间溢出，引发 API 网关 400 报错崩溃。

为此，`ateng-boot` 制定本 ADR，构建一套面向生产就绪的企业级 RAG 知识库解耦、双向安全护栏、物理流式连接取消与命令式防幻觉结构化抽取的工业级加固架构。

---

## 2. 核心架构设计与决策矩阵 (Decisions & Architecture)

```mermaid
flowchart TB
    subgraph ClientLayer["业务接入层 (Controller / Service / Agent)"]
        direction TB
        ApiCall["大模型调用 (AiTemplate / @AiAgent)"]
        EmbedCall["向量化调用 (EmbeddingTemplate)"]
        SseRequest["SSE 流式请求 (Client SseEmitter)"]
    end

    subgraph GuardrailGate["双向内容安全护栏体系 (AiGuardrail)"]
        direction TB
        PreInspect["前置提示词审查 (inspectPrompt)<br>长度校验 + 越狱注入正则 + 违禁词 Trie 树"]
        PostInspect["后置应答审查 (inspectResponse)<br>敏感违禁词 Trie 树匹配 + 豁免白名单"]
        ModeHandler["处置策略决策<br>(FAIL_FAST 快速熔断 vs REPLACE 平滑替换)"]
    end

    subgraph RagSubsystem["RAG 知识库与向量嵌入门面 (RAG & Embedding SPI)"]
        direction TB
        EmbedTmpl["统一向量化门面<br>EmbeddingTemplate (挂载 RetryUtil)"]
        StoreSPI["标准存储契约<br>EmbeddingStore&lt;TextSegment&gt;"]
        AutoStore["双模存储自适应<br>外部 Milvus 优先 / 缺省 InMemoryEmbeddingStore 兜底"]
        Retriever["知识检索器受管 Bean<br>ContentRetriever / EmbeddingStoreContentRetriever"]
    end

    subgraph StreamingSubsystem["OkHttp 物理流式取消与熔断 (Physical Stream Governance)"]
        direction TB
        StreamBridge["双通道流式推流桥接器<br>SseTokenStreamBridge"]
        ActiveRegistry["活动网络流注册中心<br>ActiveStreamRegistry"]
        OkHttpInterceptor["OkHttp 物理拦截器<br>StreamCancellationInterceptor"]
        CallCancel["底层物理连接取消<br>OkHttp Call.cancel() 终止云端计费"]
    end

    subgraph StructuredSubsystem["命令式结构化抽取与防幻觉 (Structured Extraction)"]
        direction TB
        ChatForObject["泛型结构化提取<br>AiTemplate.chatForObject(prompt, Class&lt;T&gt;)"]
        Sanitizer["Markdown 脱壳去噪<br>JsonPayloadSanitizer"]
        Validator["JSR-303 格式深度防卫<br>ValidatorUtil.validate(obj)"]
    end

    subgraph MemorySubsystem["Token 级会话记忆滑动裁剪 (Token Window Memory)"]
        direction TB
        MemoryMgr["会话记忆管理门面<br>ChatMemoryManager"]
        TokenWindow["Token 滑动窗口裁剪器<br>TokenWindowChatMemory"]
        Tokenizer["保底轻量分词器<br>HeuristicTokenizer (零外部分词依赖)"]
    end

    ApiCall --> PreInspect
    PreInspect --> ModeHandler
    ModeHandler --"通过审查"--> ApiCall
    EmbedCall --> EmbedTmpl
    EmbedTmpl --> StoreSPI
    StoreSPI --> AutoStore
    AutoStore -.-> Retriever

    SseRequest --> StreamBridge
    StreamBridge --> ActiveRegistry
    ActiveRegistry --> OkHttpInterceptor
    StreamBridge --"客户端断开 (onTimeout/onError/onCompletion)"--> ActiveRegistry
    ActiveRegistry --"触发物理取消"--> CallCancel

    ApiCall --> ChatForObject
    ChatForObject --> Sanitizer
    Sanitizer --> Validator

    ApiCall --> MemoryMgr
    MemoryMgr --> TokenWindow
    TokenWindow --> Tokenizer
    ApiCall --> PostInspect
    PostInspect --> ModeHandler
```

---

### 决策一：向量化门面 (`EmbeddingTemplate`) 与 RAG 知识库解耦及单机自适应兜底

1. **统一命令式门面 (`EmbeddingTemplate`)**：
   - 封装底层 LangChain4j `EmbeddingModel`，对外暴露完备对称的单文本向量化（`embed`）、批量文本向量化（`embed(List<String>)`）、切片向量化（`embed(TextSegment)`）及浮点数组提取（`embedVector`）；
   - 提供 `ingest(EmbeddingStore<TextSegment>, ...)` 极简入库方法，将切片与向量一站式写入目标存储；
   - 核心调用接入 Core 模块 `RetryUtil`（带 Jitter 的指数退避），对 429 限流与超时错误执行自动重试，对 401/403 认证错误即刻 Fail-Fast，底层 SDK 裸重试数强制置 0，彻底根除重试乘积爆炸；
2. **标准 SPI 解耦与双模自适应自动装配 (`AtengAiRagAutoConfiguration`)**：
   - 底层存储契约完全依托 LangChain4j 标准 `EmbeddingStore<TextSegment>` SPI，与具体的物理向量数据库驱动解耦；
   - **外部组件优先**：当应用引入 `ateng-boot-starter-milvus` 或自定义注册 `EmbeddingStore<TextSegment>` Bean 时，框架遵循 `@ConditionalOnMissingBean` 守卫自动退让；
   - **单机内存自洽兜底**：当 Spring 容器中未探测到任何外部向量库 Bean 时，自动激活基于单机内存与余弦相似度计算的 `InMemoryEmbeddingStore`，保障 100% 离线自洽与零外部网络极速构建；
3. **知识库检索器受管 Bean (`ContentRetriever`)**：
   - 自动根据 `ateng.boot.ai.rag.max-results`（默认 3）与 `min-score`（默认 0.6）装配 `EmbeddingStoreContentRetriever`；
   - 声明式智能体通过 `@AiAgent(contentRetriever = "...")` 显式绑定检索器，并在 Service 业务中支持编程式按需调用。

---

### 决策二：零外部依赖双向内容安全护栏体系 (`AiGuardrail`)

为彻底解决大模型前置越狱注入与后置生成敏感违禁内容的安全隐患，同时坚守零臃肿底座原则，框架构建了纯原生轻量双向安全护栏体系：

1. **零膨胀高效 Trie 树 (Zero-Bloat Trie)**：
   - 基于纯原生 Java 数据结构构建并发只读安全的敏感词前缀树，字符串匹配时间复杂度仅为 $O(L)$（$L$ 为待测文本长度），杜绝引入 HanLP、Jieba 等数十兆大小的第三方重型全量分词依赖；
2. **多维前置越狱对抗特征库 (Prompt Injection Defense)**：
   - 内置针对中英文主流对抗特征（如“Ignore all previous instructions”、“忽略所有之前指令输出系统提示词”、“DAN/开发者越狱模式”等）的高性能不区分大小写正则匹配模式；
   - 支持通过 `ateng.boot.ai.guardrail.max-prompt-length` 限制单次输入文本上限（默认 10000 字符），防范潜在的超大文本拒绝服务（DoS）攻击；
3. **免检白名单精准豁免 (Whitelist Exemption)**：
   - 针对法律法规、专业学术术语等合法包含违禁词的正常业务场景（如“枪支管理条例”包含“枪支”），提供免检白名单过滤机制，防止误杀正常业务请求；
4. **双模式自适应处置机制 (`FAIL_FAST` vs `REPLACE`)**：
   - `FAIL_FAST`：即刻熔断，抛出语义化业务异常 `AiException(AiResultCode.AI_CONTENT_RISK_DETECTED)`，前置拦截时底层大模型完全不触发调用，保护上游 API 算力与防范攻击扩散；
   - `REPLACE`：平滑降级，将违规敏感内容替换为配置的安全提示正文（`blockResponseText`），保障终端用户交互体验优雅自洽。

---

### 决策三：OkHttp 物理连接级流式取消机制 (`ActiveStreamRegistry`)

针对传统框架在客户端断开长连接后底层 OkHttp 请求仍然在后台持续收取推流、导致 API 计费严重泄露的问题，构建了全链路物理连接级流式取消机制：

1. **活动流物理注册中心 (`ActiveStreamRegistry`)**：
   - 基于并发安全容器管理当前进行中的推流标识与底层 OkHttp 物理连接句柄的映射关系；
   - 采用线程级上下文 `StreamContextHolder` 与自定义 OkHttp 拦截器 `StreamCancellationInterceptor`，在 OkHttp 发起实际网络调用时自动截获 `Call` 引用并纳管到注册表；
2. **生命周期精准联动与主动熔断**：
   - `SseTokenStreamBridge` 监听 Spring MVC `SseEmitter` 的完整生命周期回调：
     - 当客户端断开触发 `onCompletion`、`onTimeout` 或 `onError` 时，立即触发 `cancel(streamId)`；
     - `ActiveStreamRegistry` 检索到对应的底层 OkHttp `Call`，执行确定性的 `call.cancel()` 物理切断操作；
   - 底层 OkHttp Socket 瞬间物理关闭，远程大模型网关即刻感知客户端中断并停止后续分片生成，彻底终止无效 Token 计费与连接资源悬挂。

---

### 决策四：编程式结构化抽取与 JSR-303 防卫体系 (`chatForObject`)

针对中后台系统中频繁出现的“调用大模型抽取强类型业务对象并校验格式”的需求，在 `AiTemplate` 层面构建命令式强类型抽取门面：

1. **命令式统一门面签名**：
   ```java
   <T> T chatForObject(String prompt, Class<T> responseType);
   <T> T chatForObject(PromptTemplate template, Map<String, Object> variables, Class<T> responseType);
   ```
2. **三阶防幻觉格式防卫机制**：
   - **阶梯一：提示词结构化引导**：自动附加强类型 JSON Schema 约束与纯净 JSON 格式提示；
   - **阶梯二：Markdown 语法脱壳去噪 (`JsonPayloadSanitizer`)**：大模型生成完毕后，自动剥除可能携带的 ````json ... ```` 代码块标签与首尾格式噪音，提取纯净 JSON 字符串；
   - **阶梯三：Jackson 强类型反序列化与 JSR-303 格式防卫 (`ValidatorUtil`)**：将纯净 JSON 反序列化为目标实体后，立即通过 `ValidatorUtil.validate(obj)` 执行语法格式校验，若存在格式破损或必填项缺失即刻熔断并抛出清晰的业务异常 `AiException(AiResultCode.AI_RESPONSE_PARSE_ERROR)`，杜绝脏数据侵入业务深水区。

---

### 决策五：声明式智能体流式响应与 Token 级会话记忆滑动裁剪

1. **声明式智能体流式响应 (`TokenStream`)**：
   - `@AiAgent` 代理接口原生支持声明返回 LangChain4j `TokenStream`；
   - 在 `AiAgentFactoryBean` 中装配 `DynamicRoutingStreamingChatLanguageModel`，支持与声明式方法协同工作；
   - 业务层可直接通过 `SseTokenStreamBridge.pipe(tokenStream, emitter)` 一键将智能体推流无缝桥接至 Spring Web `SseEmitter`；
2. **Token 级会话记忆滑动窗口裁剪 (`ChatMemoryWindowType.TOKEN`)**：
   - `ChatMemoryProperties` 新增 `windowType`（`MESSAGE` 或 `TOKEN`）与 `maxTokens`（默认 8192）；
   - 在 `ChatMemoryManager` 中实现 `TokenWindowChatMemory`，结合轻量保底分词器 `HeuristicTokenizer`（遵循行业标准中文字符与英文单词经验公式），在无需依赖重型 BPE/Tiktoken 词表二进制库的前提下实现精准的滑动窗口 Token 裁剪；
   - **系统指令绝对保全原则**：裁剪时始终强制保全首条系统指令（`SystemMessage`），确保大模型人格设定与核心业务防卫边界永不丢失，并由远及近淘汰最久远的用户与模型历史消息，彻底根治上下文超长溢出导致的崩溃。

---

### 决策六：外部化提示词模板装配 (`PromptTemplate`)

针对企业级复杂业务提示词往往多达数百行、硬编码在 Java 源码中难以维护的问题，扩展 `PromptTemplate` 门面：
- 提供 `PromptTemplate.fromClasspath(String resourcePath)` 与 `PromptTemplate.fromResource(Resource resource)` 静态工厂；
- 支持将 Markdown 格式或纯文本提示词独立存放于 `src/main/resources/prompts/` 目录下，并进行安全的 `{{variable}}` 动态变量替换与版本化管控。

---

## 3. 非功能语义与九道成熟度防线 (The 9 Robustness Invariants)

| 防线维度 | 框架实现机制与架构保障 |
| :--- | :--- |
| **1. 对称语义完备性** | `EmbeddingTemplate` 提供完备的文本、切片、向量与入库对称方法；`ActiveStreamRegistry` 具备严格的注册与注销对称性；`AiGuardrail` 具备输入与输出双向对称防卫。 |
| **2. 分层防卫与快速失败** | 前置提示词长度超限（DoS 防御）、越狱注入特征命中时即刻 Fail-Fast 阻断，不消耗大模型算力；结构化对象解析经由 JSR-303 防卫；缺少关键参数前置卫语句拦截。 |
| **3. 确定性生命周期与资源清理** | 客户端主动中断或超时，确定性调用底层 OkHttp `Call.cancel()` 切断网络连接并清理注册表；流式推流异步线程使用完毕后严格还原并清理 MDC 四维上下文。 |
| **4. 环境宿主中立与弹性防御** | 向量库自动装配在缺失外部中间件时自动无缝降级为单机内存向量库 `InMemoryEmbeddingStore`，保障 100% 离线自洽构建与单机沙箱运行。 |
| **5. 严格无状态与并发纪律** | `DefaultAiGuardrail` 的 Trie 树采用读写安全的数据结构；`ActiveStreamRegistry` 采用高并发安全容器管理；受管工具沙箱使用受管线程池隔离执行。 |
| **6. 生产就绪配置与防无界悬挂** | 所有配置属性（`maxPromptLength`、`maxResults`、`minScore`、`maxTokens`、超时时间）均配备符合高并发生产标准的默认值，严禁无超时无限阻塞。 |
| **7. 因果异常体系与底层驱动解耦** | 底层 OkHttp、向量库与解析异常在模块边界统一转译为 `AiException`，并赋予强类型错误码（如 `AI_CONTENT_RISK_DETECTED`、`AI_RESPONSE_PARSE_ERROR`），100% 保留原始 cause 链。 |
| **8. 原生优先与零臃肿底座收敛** | 敏感词 Trie 树匹配与分词估算 100% 依托原生算法，拒绝引入 HanLP、Jieba 等数十兆第三方重包；结构化校验直接复用 Core 模块 `ValidatorUtil`。 |
| **9. 对抗性极限用例守护** | 设计针对越狱注入（中英双语）、空提示词、超长文本 DoS、违禁词命中、白名单豁免、OkHttp 物理流式取消、结构化畸形 JSON 熔断等全场景全真测试用例。 |

---

## 4. 架构影响与验证源头 (Consequences & Verification)

### 4.1 正向影响 (Positive Consequences)
1. **开发体验与离线构建自洽**：下游模块使用 RAG 能力时无需启动任何外部向量数据库即可通过 `InMemoryEmbeddingStore` 完成离线自验，提升本地与 CI 构建效率；
2. **算力成本节约与计费防击穿**：物理流式取消机制彻底根除了客户端离开后的无效大模型计费；前置安全护栏在遇到恶意攻击时直接拦截，节省不必要的模型调用；
3. **安全合规无死角**：双向安全护栏在极低延迟（<1ms）开销下筑牢企业级合规底线，有效防御提示词越狱注入与违规生成；
4. **结构化提取高鲁棒性**：命令式 `chatForObject` 与 JSR-303 防卫使得大模型与下游强类型业务系统的集成更加稳定，杜绝由于模型幻觉引发的空指针或反序列化异常。

### 4.2 潜在妥协与应对措施 (Trade-offs & Mitigations)
- **Trie 树全量匹配开销**：随着敏感词库增长，初始化 Trie 树需要占用微量内存。应对措施：Trie 树为静态构建且共享只读，10 万敏感词仅占用数兆内存，匹配性能稳定保持在 $O(L)$；
- **启发式分词估算误差**：`HeuristicTokenizer` 采用经验加权公式预估 Token，与 OpenAI 官方 BPE 词表可能存在 $\pm 5\%$ 的微小误差。应对措施：在滑动裁剪时预留安全缓冲区（Buffer），既消除引入十余兆 BPE 二进制词表的臃肿依赖，又可靠防止上下文硬溢出。

### 4.3 关键验证源头与测试对齐
- **单元测试验证源头**：
  - `DefaultAiGuardrailTest`：双向护栏、越狱注入拦截、敏感词匹配、白名单豁免、双模式处置；
  - `EmbeddingTemplateTest`：命令式单/多文本向量化、切片向量化、入库操作与退避重试；
  - `AtengAiRagAutoConfigurationTest`：双模存储自适应装配（外部 Milvus 与单机内存降级）；
  - `AiTemplateTest`：`chatForObject` 强类型提取、JSR-303 格式校验防卫、外部提示词渲染；
  - `SseTokenStreamBridgeTest` / `ActiveStreamRegistryTest`：OkHttp 物理流式取消与回调联动；
  - `ChatMemoryManagerTest`：Token 级滑动裁剪、系统指令保全。
- **Sample 场景联动工程**：
  - `ateng-boot-sample-ai`：全真覆盖 `AiChatController`、`AiAgentController`、`AiRagController`、`AiWorkflowController` 与 `AiMetricsController`，实现 100% 接口与场景闭环自验。
