# 架构决策记录看板 (Architectural Decision Records)

本目录记录 `ateng-boot` 基础框架演进过程中的所有关键技术与架构决策（ADR）。每一篇记录均遵循统一的背景、决策内容、后果分析与权衡考量，作为团队协作与长期演进的权威准绳。

---

## 架构决策矩阵 (Decision Matrix)

| 编号 | 决策标题 | 状态 | 核心收益与权衡 | 核心关联模块 | 详细文档 |
| :---: | :--- | :---: | :--- | :--- | :---: |
| **0001** | 顶级品牌多仓解耦与通用基础设施底座 | `Accepted` | 确立 `ateng` 顶级品牌，`ateng-boot` 作为单体与微服务通用底座 | 全工程 | [查看](./0001-multi-repo-brand-matrix.md) |
| **0002** | 显式统一返回契约，严禁全局拦截包装 | `Accepted` | Controller 显式声明 `Result<T>`，彻底消除 `@ResponseBodyAdvice` 对 String、下载及 Swagger 的侵入 | `ateng-boot-core` | [查看](./0002-explicit-result-contract-no-response-body-advice.md) |
| **0003** | 基于 BOM Import 机制的无侵入依赖统管 | `Accepted` | 采用 `<type>pom</type><scope>import</scope>`，彻底摆脱单继承父 POM 锁定枷锁 | `ateng-boot-bom` | [查看](./0003-dependency-management-via-bom-import.md) |
| **0004** | 传输层 HTTP 200 与业务错误脱敏拦截 | `Accepted` | HTTP 协议层保持 200，内部通过业务状态码区分；未知根异常强制生产脱敏并关联 `traceId` | `ateng-boot-web` | [查看](./0004-http-200-with-business-code-and-exception-masking.md) |
| **0005** | 基于 Jackson Customizer 注入全局序列化 | `Accepted` | 统一通过 `Jackson2ObjectMapperBuilderCustomizer` 注入雪花 Long 转 String 与时间格式化，严禁覆盖 `ObjectMapper` | `ateng-boot-web` | [查看](./0005-jackson-customizer-over-objectmapper-override.md) |
| **0006** | 技术依赖基线锁定与安全升级策略 | `Accepted` | 字节码锁定 JDK 17，Spring Boot 锁定 3.3.13，MyBatis-Plus 锁定 3.5.17，全局屏蔽破坏性大版本 | 全工程 | [查看](./0006-dependency-baseline-and-upgrade-strategy.md) |
| **0007** | Maven Central 官方发布解耦与轻量极速 CI | `Accepted` | 非分发模块 `<maven.deploy.skip>true</maven.deploy.skip>`，异步校验 `<waitUntil>validated</waitUntil>` 极速解耦 | 全工程 | [查看](./0007-maven-publishing-and-clean-ci-pipeline.md) |
| **0008** | 自动化发版、单一版本流与常青文档决策 | `Accepted` | 彻底告别传统 SNAPSHOT 快照流，主干停留在最新稳定版；CI 智能短路放行；全链路防呆强熔断 | 全工程 / Scripts | [查看](./0008-automated-release-changelog-and-evergreen-docs.md) |
| **0009** | main 稳定发布与 dev 活跃开发双分支架构 | `Accepted` | 解耦生产发布与日常编码，日常活跃开发收口于 `dev`，正式发版严格收口于只读受保护的 `main` | 全工程 / Git 协作 | [查看](./0009-dual-branch-workflow-main-release-and-dev-active.md) |
| **0010** | 传输契约与持久层解耦与流式转换工具 | `Accepted` | 传输层解耦持久层 ORM，提供纯净 `PageResult<T>`、`PageQuery` 与 `PageUtil` 流式转换门面 | `core` / `mybatis-plus` | [查看](./0010-transport-pagination-contract-decoupling.md) |
| **0011** | 零重型外部依赖与上下文就近内聚工具集 | `Accepted` | 彻底杜绝全量外部工具包，100% 依托 JDK 17 与 Spring 6，依运行时就近内聚 | `core` / `web` / `mybatis-plus` | [查看](./0011-zero-bloat-and-co-located-utilities.md) |
| **0012** | 数据安全生命周期分离：存储加密与视图脱敏 | `Accepted` | 确立“内存真明文”不变量，存储加密收敛于 TypeHandler，展示脱敏收敛于 Jackson | `core` / `mybatis-plus` | [查看](./0012-lifecycle-separated-data-security.md) |
| **0013** | 基于 SPI 抽象的混合式接口幂等防重引擎 | `Accepted` | 确立两级存储 SPI 自适应装配，内聚内存防爆与 Redis 无缝升级，全生命周期锁语义与 SpEL 支持 | `ateng-boot-web` | [查看](./0013-spi-based-hybrid-idempotent-engine.md) |
| **0014** | 基于三级自适应 SPI 架构的接口限流防刷引擎 | `Accepted` | 统一令牌桶模型，纯 JDK 原子无锁单机兜底与 Redis/Redisson 集群自适应，复合维度与 HTTP 429/Retry-After | `ateng-boot-web` | [查看](./0014-three-tier-adaptive-rate-limiter-engine.md) |
| **0015** | 基于 SPI 扩展与凭证安全反射的动态 CORS 架构 | `Accepted` | 深度集成 Spring 原生 CorsConfigurationSource，支持动态来源 SPI 与带凭据安全反射 Vary 响应头 | `ateng-boot-web` | [查看](./0015-dynamic-cors-and-credentials-safe-origin-reflection.md) |
| **0016** | 双向 API 数据加签验签与防重放引擎 | `Accepted` | 纯 JDK 17 原生零膨胀签名 SPI，标准化待签串，复用 IdempotentKeyStore 防重放，AOP 切面遵守 ADR-0002 | `ateng-boot-web` | [查看](./0016-dual-direction-api-signature-and-anti-replay-engine.md) |
| **0017** | 企业级行级多租户安全隔离与自动填充架构 | `Accepted` | 深度集成 MP 3.5.17 TenantLineInnerInterceptor，数字列索引保护，@IgnoreTenant 与闭包短路，智能插入填充 | `ateng-boot-mybatis-plus` | [查看](./0017-multi-tenant-row-level-isolation-architecture.md) |
| **0018** | 基于 JSqlParser AST 的声明式细粒度数据权限引擎架构 | `Accepted` | 声明式 @DataScope 注解，多表别名校验，DataPermissionHandler SPI 解耦，超管与闭包秒级短路，分页 count 自动保护 | `ateng-boot-mybatis-plus` | [查看](./0018-declarative-data-permission-engine-architecture.md) |
| **0019** | 原生高性能分批批量插入引擎架构决策 | `Accepted` | 挂载 InsertBatchSomeColumn 单条多值 SQL，过滤逻辑删除列，BatchUtil 智能切片与 AtengBaseService 强事务守护 | `ateng-boot-mybatis-plus` | [查看](./0019-native-high-performance-batch-insert-engine.md) |
| **0020** | 零膨胀分布式 ID 引擎与全场景敏感数据脱敏架构决策 | `Accepted` | 纯 JDK 17 原生 Snowflake/IdUtil 引擎，四级自适应节点，全场景脱敏策略扩展与持久层发号器大一统 | `core` / `mybatis-plus` | [查看](./0020-zero-bloat-distributed-id-and-mask-strategy.md) |
| **0021** | 存量核心基线收敛与零冗余封版战略决策 | `Accepted` | 宣布三大核心模块功能完备，横向冻结新模块分裂，微创补强 ServletUtil 流式下载，极限测试加固与文档大一统 | `core` / `web` / `mybatis-plus` | [查看](./0021-core-baseline-convergence-and-zero-bloat-freeze-strategy.md) |
| **0022** | 双核驱动 Redis 基础设施与企业级分布式协同架构决策 | `Accepted` | 双核驱动融合（Spring Data Redis + Redisson 原生），纯净 JSON 序列化防 @class 污染，@DistributedLock 4 阶模式与慢锁审计，延时队列与全能 RedisUtil | `ateng-boot-redis` | [查看](./0022-dual-core-redis-and-distributed-coordination-architecture.md) |
| **0023** | 企业级轻量流式 Excel 引擎与数据安全/校验/防呆一体化架构决策 | `Accepted` | 锁定 Apache Fesod 流式基线，导出动态脱敏（@ExcelSensitive 守卫 ADR-0012 内存真明文），JSR-303 行级校验与红字批注回写，防呆模板下拉提示，双向枚举/字典转义，复杂报表填充合并与多 Sheet 编排 | `ateng-boot-excel` | [查看](./0023-streaming-excel-engine-and-data-security-architecture.md) |
| **0024** | 企业级 Sa-Token 鉴权生态与安全防御/分布式会话一体化架构决策 | `Accepted` | 独立 Starter，双轨拦截防御，自适应存储桥接，强类型 LoginUser/LoginHelper，底座全链路贯通，两级权限缓存与 Spring 原生事件广播 | `ateng-boot-satoken` | [查看](./0024-enterprise-satoken-authentication-and-security-architecture.md) |
| **0025** | Core 核心底座全方位扩建与未来中间件孵化地基架构决策 | `Accepted` | 序列化门面 JsonUtil 下沉、脱敏/租户上下文彻底归位、四维异步透传闭环、函数式 Result 演化与 SpringUtil/LambdaUtil/RetryUtil 等未来孵化原语矩阵 | `ateng-boot-core` / 全工程 | [查看](./0025-core-module-expansion-and-future-proof-middleware-foundations.md) |
| **0026** | 生产级基线加固与底层设施大一统架构决策 | `Accepted` | 全域 JSON 100% 收敛 JsonUtil、双维上下文镜像对称与 Sa-Token 自动无感桥接、TraceFilter 无锁原语、异步线程池下沉 core、底座原语复用与延时队列 MDC 追踪 | `core` / `web` / `satoken` / `redis` / `excel` | [查看](./0026-production-hardening-and-foundations-unification.md) |
| **0027** | 分层双防数据校验体系与标准化原语架构决策 | `Accepted` | 确立分层双防铁律，沉淀 ValidationGroups 标准分组契约与 ValidList 批量级联容器，扩展 ValidatorUtil POJO 编程式校验与无 EL 优雅降级 | `core` / `web` | [查看](./0027-defense-in-depth-validation-and-standardized-primitives.md) |
| **0028** | 示例工程多模块解耦与精准增量集成测试架构决策 | `Accepted` | 二级 Parent POM 聚合解耦，自包含场景子模块矩阵（零共享代码），秒级精准增量测试门禁与发布双层排除隔离 | `ateng-boot-samples` / 全工程 | [查看](./0028-samples-modularization-and-incremental-integration-testing.md) |
| **0029** | 基于 Spring Boot 3.3 与 JDK 21+ 的自适应虚拟线程异步运行时架构决策 | `Accepted` | 官方原生 @ConditionalOnThreading 声明式分流，SimpleAsyncTaskExecutor 虚拟线程自适应，四维上下文全生命周期透传，Snowflake 升级 ReentrantLock 彻底杜绝 Carrier Pinning | `ateng-boot-core` / 全工程 | [查看](./0029-virtual-threads-and-adaptive-async-runtime.md) |
| **0030** | 企业级轻量多源对象存储与安全预签名一体化架构决策 | `Accepted` | Local + S3 统一驱动，排除 Netty 零膨胀，智能双模配置，内置 HMAC 预签名平替，三位一体动态切源，StorageResource 断点续传与纯内存自验门禁 | `ateng-boot-storage` / 全工程 | [查看](./0030-storage-module-architecture.md) |
| **0031** | 外部动态 SQL 引擎与运行时热更新架构决策 | `Accepted` | SPI 契约解耦外部载体，智能容错包裹 <script>，无锁受控热替换绕过 StrictMap 冲突与回滚防灾，双模驱动深度融合 ADR-0010 分页契约与拦截器透明继承 | `ateng-boot-mybatis-plus` | [查看](./0031-external-dynamic-sql-engine-and-hot-reload-architecture.md) |
| **0032** | 运行时安全与流量治理架构决策 | `Accepted` | 通用请求体可重读管道、结构化操作审计切面与 SPI、K8s 云原生就绪探针排空治理、慢请求主动告警与 HTTP Payload 双向采样截断防 OOM | `ateng-boot-web` / `core` | [查看](./0032-web-runtime-audit-payload-and-traffic-governance.md) |
| **0033** | 持久层生产级加固与流式游标防 OOM 架构决策 | `Accepted` | 唯一索引占位删除实体 BaseDeleteIdEntity，原生批量插入自愈补偿 BatchUtil，慢 SQL 性能审计与防雪崩 SlowSqlInterceptor，受管游标流式查询门面 StreamingQueryTemplate 与多数据源协同诊断探针 DynamicDatasourceGuard | `ateng-boot-mybatis-plus` | [查看](./0033-mybatis-plus-production-hardening-and-streaming-architecture.md) |
| **0034** | 动态查询转译、双方言批量增强与数据变更审计架构决策 | `Accepted` | 动态查询条件转译引擎与 URL 冒号语法解析器（六重安全防卫与作用域 OR 分组），双方言原生批量 Upsert 注入器与主键排序防死锁引擎，数据变更 Diff 审计拦截器与防爆熔断三级治理 | `ateng-boot-mybatis-plus` / `core` | [查看](./0034-dynamic-query-multidialect-upsert-and-data-audit-architecture.md) |
| **0035** | 声明式与命令式双模 HTTP 通信底座与多命名客户端架构决策 | `Accepted` | Spring 6 原生轻量扫描驱动（`@EnableHttpInterfaces` / `@HttpClient`），多命名客户端注册表 `RestClientRegistry` 与物理连接池唯一共享，四维上下文域隔离透传，cURL 脱敏日志，幂等退避重试与虚拟线程高并发协同 | `ateng-boot-http` / `core` | [查看](./0035-declarative-and-imperative-http-client-architecture.md) |
| **0036** | 实时通信多节点集群协同、防反射广播与优雅停机排空架构决策 | `Accepted` | 运行时无侵入 Redisson 探测与单机降级，Redis Pub/Sub 去中心化广播与 `sourceNodeId` 防反射风暴，可拔插离线消息暂存 SPI，Sa-Token 封禁事件联动切断，SmartLifecycle 四阶优雅停机排空与高吞吐集群度量 | `ateng-boot-realtime` / `redis` / `satoken` | [查看](./0036-realtime-cluster-and-distributed-coordination.md) |
| **0037** | RabbitMQ 生产级路由与可靠投递底座架构决策 | `Accepted` | 基于 Spring AMQP 官方基线，四维上下文透明透传，Publisher Confirms 与 Returns 双重保障，事务感知 sendAfterCommit 杜绝幽灵消息，INFERRED 泛型推断反序列化解耦微服务契约，消费端幂等静默确认与毒丸快速隔离，死信终点不变量强校验，RabbitTopologyBuilder 声明式拓扑与 100% 离线自洽集成测试沙箱 | `ateng-boot-rabbitmq` / `core` | [查看](./0037-rabbitmq-production-routing-and-reliable-delivery-architecture.md) |
| **0038** | Kafka 高性能流批处理与 Offset 治理底座架构决策 | `Accepted` | 基于 Spring Kafka 官方基线，微批与幂等生产高吞吐调优，事务感知 sendAfterCommit 杜绝幽灵消息，ErrorHandlingDeserializer 反序列化崩溃防卫，FatalExceptionClassifier 毒丸快速隔离至 *.DLT，双容器工厂正交共存，KafkaRecordExecutor 批量消费多租户严格隔离，@Idempotent 静默提交 Offset，CooperativeStickyAssignor 渐进式协作重平衡与 10 秒优雅停机，Micrometer 云原生可观测性与 @EmbeddedKafka 100% 离线自洽测试沙箱 | `ateng-boot-kafka` / `core` | [查看](./0038-kafka-high-throughput-streaming-and-offset-governance-architecture.md) |
| **0039** | RocketMQ 5.x gRPC 生产增强与原生分布式事务架构决策 | `Accepted` | 基于 RocketMQ 5.x 官方 gRPC 瘦客户端基线 (5.2.2 Shaded)，受管高阶门面 RocketmqTemplate，原生 2PC 分布式事务半消息与 RocketmqTransactionChecker 统一回查路由分发，事务感知 sendAfterCommit 杜绝幽灵消息，声明式 @RocketmqListener 容器与推断反序列化解耦微服务契约，消费端幂等静默确认与毒丸快速丢弃阻断自旋，大报文 4MB 前置防御与 4KB 透明压缩，SmartLifecycle 确定性停机排空与 100% 离线自洽 TestDouble 测试沙箱 | `ateng-boot-rocketmq` / `core` | [查看](./0039-rocketmq-grpc-and-native-transaction-architecture.md) |
| **0040** | 云原生可观测性探针与 Actuator 生产级加固架构决策 | `Accepted` | 官方原生 Actuator 与 Micrometer Prometheus 基线，黄金解耦 Liveness 与 Readiness，弱依赖探针超时熔断熔断保护，高危端点 Token 鉴权，受管线程池动态打标与黄金指标采集 | `ateng-boot-observability` / `core` | [查看](./0040-cloud-native-observability-probes-and-actuator-hardening-architecture.md) |
| **0041** | 纯原生 SpringDoc 架构选型与接口文档生产治理防护决策 | `Accepted` | 全面拥抱 SpringDoc 官方原生生态（Swagger UI 5.x），彻底告别停更类库；生产 404 物理切断与 Basic/Sa-Token 运行时双模访问守卫；全链路追踪与多租户标头自动注入；Sa-Token 锁头鉴权自动注册；状态码全景字典注入与标准响应补全；配置驱动多业务分组路由与 /docs 极简重定向 | `ateng-boot-openapi` / `core` / `web` | [查看](./0041-pure-springdoc-openapi-and-production-guard-architecture.md) |
| **0042** | 工业级异步邮件与模板通知底座架构设计决策 | `Accepted` | 专注出站 SMTP 生产加固；多命名账号注册表与动态多租户解析；受管异步与四维快照透传；模板 SPI 解耦与极简正则保底；流式大附件防 OOM 与 RFC 6266 中文规范；本地事务感知 sendAfterCommit 与三重失败补偿安全网；MDC traceId 与 Message-ID 双向追踪；GreenMail 100% 离线自洽单测沙箱 | `ateng-boot-mail` / `core` | [查看](./0042-industrial-grade-asynchronous-mail-and-template-architecture.md) |

---

## ADR 撰写与维护规范

新增或修订架构决策时，请严格遵守以下准则：

1. **触发门槛 (Three Prerequisites)**：
   - **难以逆转 (Hard to reverse)**：推翻重来的技术或沟通成本较高；
   - **脱敏需要解释 (Surprising without context)**：未来的开发者查阅代码时会产生“为什么这么写”的疑问；
   - **权衡折衷产物 (Real trade-off)**：在多个有明确利弊的备选方案中经过推演做出的取舍。
2. **编号与命名**：统一采用 4 位递增序号与短横线命名，如 `0009-distributed-lock-redis-integration.md`。
3. **结构要素**：必须包含【背景与痛点】、【决策内容与落地设计】及【后果与权衡（正向收益与妥协成本）】。
4. **膨胀防线与合并收敛 (Anti-Inflation & Consolidation)**：
   - **严禁碎片化滥立 ADR**：单个工具类微调、常规方法扩展、业务小修补坚决禁止新建独立 ADR；
   - **优先增量修订既有篇目**：针对已就绪模块的功能增强与基线加固，优先在既有归属 ADR（如基线加固并入 ADR-0026、校验设施并入 ADR-0027、核心底座原语并入 ADR-0025）中以增量章节演进；仅在孵化完整新中间件底座、重大跨模块协议契约重构或颠覆性路线变革时，方可分配新编号立篇。
