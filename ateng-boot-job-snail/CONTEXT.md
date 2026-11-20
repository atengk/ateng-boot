# SnailJob 分布式任务调度与分布式重试加固底座

面向企业级微服务分布式任务调度、复杂工作流（DAG）、Map/MapReduce 动态分布式切片计算、分布式业务异常重试（Local / Remote）与死信治理的基础设施，基于官方 `com.aizuda:snail-job-client-starter:1.10.0`（原生 Spring Boot 3 与 JDK 17 基线）深度加固，提供独立单注解自动扫描注册与增强 `@AtengSnailJob` / `@AtengSnailRetry`（兼容原生 `@JobExecutor` / `@Retryable`）、四维全链路上下文 `RedisSnailRetryContextStore` Fail-Open 集群保真还原（`RetryContextSnapshotPayload` / `AsyncContext` / MDC `traceId` / 租户模式 / 操作人）、SLF4J 动态日志镜像无感推送（`SnailJobLogbackAppender` + 双向 `maxPayloadSize` 防护）、本地硬看门狗超时中断熔断与方法级瞬态退避重试/告警覆写/就地降级、强类型 DTO 入参与 JSR-303 防卫、大数据分片流式批处理与多租户并发背压模板（`SnailShardingBatchTemplate` / `TenantJobTemplate` / `BatchFailureHandler`）、编程式重试门面（`SnailRetryTemplate`）、OpenAPI 编程式管理门面（`SnailJobAdminTemplate`）、非阻塞缓存双擎健康探针（`SnailJobHealthIndicator`）、三阶告警防风暴聚合自愈与死信领域事件直通通道、K8s 停机三阶平滑排空状态机、节点级运行时治理总线（`SnailJobRuntimeGovernor`），以及统一调度内核支撑的 Actuator 运维端点与 100% 离线自洽单测沙箱（`SnailJobInvocationSupport` / `SnailJobTestHelper`）。

## Language

**SnailJob (分布式双擎受管单元)**:
由独立 `@AtengSnailJob` / `@AtengSnailRetry` 或原生 `@JobExecutor` / `@Retryable` 声明的分布式受管单元，支持常规定时调度、工作流（DAG）、Map/MapReduce 动态切片计算，以及本地/远程分布式异常重试。
_Avoid_: 定时器, 后台线程, 重试切面, EasyRetryJob, 强制双注解冗余

**AtengSnailJob (高阶声明式调度注解)**:
既可完全替代原生 `@JobExecutor` 独立声明调度执行器（由 `AtengSnailJobScanner` 对接注册，支持 `value` / `name` 别名及类级 `method`），也可增强原生执行器与切片注解；提供方法级本地硬超时阈值（`timeout`）、超时降级补偿方法（`fallbackMethod`）、方法级瞬态异常退避重试（`retryCount` / `retryInterval`，对参数校验、JSON 解析、负载超限与超时强制 0 次快速失败）、方法级告警开关/抑制阈值覆写（`alertEnabled` / `alertSuppressThreshold`）、多租户隔离模式（`tenantMode`）与强类型入参 DTO 自动反序列化。
_Avoid_: SnailJob增强注解, 自定义Job, JobAnnotation, 必须双写JobExecutor

**AtengSnailRetry (高阶声明式重试注解)**:
可独立声明分布式重试方法（由 `AtengSnailRetryScanner` 对接 `RetryableRegistrar` 并由 `AtengSnailRetryPointcutAdvisor` 织入本地/远程重试闭环，支持 `scene` / `value` 别名），同时向后兼容直写原生 `@Retryable`（混用时显式 `@Retryable` 优先生效）；内置 SpEL 业务幂等键（`idempotentSpel`）、死信沉降回调（`SnailRetryDeadLetterCallback`）与四维上下文快照绑定。
_Avoid_: 重试增强, 业务重试, RetryAnnotation, 必须双写Retryable

**AtengSnailJobScanner / AtengSnailRetryScanner (独立注解执行器扫描器)**:
分别对接 SnailJob 官方 `JobExecutorRegistrar` 与 `RetryableRegistrar` 的 `Scanner` SPI，自动发现仅声明 `@AtengSnailJob` 或 `@AtengSnailRetry` 的执行器与重试方法；对重复任务名在启动期 Fail-Fast 阻断，对已显式标注原生 `@JobExecutor` / `@Retryable` 的方法幂等让渡。
_Avoid_: 手动注册器, ReflectionScanner

**JobTenantMode (任务租户隔离策略)**:
标识任务在调度期间的多租户隔离边界策略：`IGNORE`（平台级全局扫描，忽略租户限制）、`PARAM`（从调度参数中提取 `tenantId` 独立挂载）、`ITERATE`（租户级沙箱轮询，依次独立事务或受管并发执行）。
_Avoid_: 租户级别, 隔离模式, TenantPolicy

**SnailJobInterceptor (调度生命周期责任链拦截器)**:
调度任务执行生命周期责任链扩展点，提供 `preHandle`、`postHandle`、`afterCompletion` 三个挂载点，支持企业挂载安全校验、金丝雀灰度、审计追溯与动态数据源切换。
_Avoid_: JobFilter, 任务监听器, SnailAspect

**JobTimeoutFallbackHandler (调度超时降级处理器)**:
当调度或切片任务被本地硬看门狗判定为超时并强行中断时触发的兜底降级 SPI 契约，支持执行本地补偿、分布式锁解除或外部状态回滚。
_Avoid_: 超时补丁, FallbackService, 超时回调接口

**SnailRetryContextStore (分布式重试现场存储门面)**:
为分布式远程重试四维上下文快照提供读写与 TTL 淘汰抽象的存储 SPI；默认在单机下启用基于 `ReentrantLock` 保护的 `InMemorySnailRetryContextStore`（默认上限 `in-memory-max-size=10000`），当存在 `StringRedisTemplate` 时自动优先装配 `RedisSnailRetryContextStore`。
_Avoid_: 上下文缓存, ContextCache, RetryStorage, synchronized同步存储

**RedisSnailRetryContextStore & RetryContextSnapshotPayload (Fail-Open 集群重试快照存储与传输载荷)**:
`RedisSnailRetryContextStore` 是基于 `StringRedisTemplate` 实现的 Fail-Open 分布式重试上下文存储实现（键前缀 `ateng:boot:job:snail:retry:ctx:`，受 `retry.context-ttl` 控制 TTL）；存储前将不可直接反序列化的 `AsyncContext.ContextSnapshot` 编码为扁平可序列化模型 `RetryContextSnapshotPayload`，读取时通过 Core 公开原语重建运行时快照，发生 Redis 网络/序列化异常时静默降级本地有界内存存储。
_Avoid_: ContextSnapshot直接反序列化, JsonUtil.parseObject(ContextSnapshot.class), Fail-Close缓存

**FailedBatch (批处理失败分片游标模型)**:
记录 `SnailShardingBatchTemplate` 或 `TenantJobTemplate` 在步长/游标/租户消费过程中失败单元的元数据模型，包含起止游标（或租户标识）、分片索引、批次序号、条数与底层异常原因；结果载体 `BatchExecutionResult` 通过 `MAX_FAILED_ITEMS = 500` 有界不可变集合截断防 OOM。
_Avoid_: 失败记录, BatchError, ErrorItem

**BatchFailureHandler (批处理失败切片函数式回调 SPI)**:
继承自 `Consumer<FailedBatch<K>>` 的函数式回调 SPI（支持作为全局默认 `@Bean` 注入或调用方局部覆盖，二者不重复触发），用于在单批或单租户执行失败时实时沉降死信、落库或告警。
_Avoid_: ErrorCallback, FailureListener

**SnailJobRuntimeGovernor (节点级任务运行时治理总线)**:
负责单个服务节点内存中受管任务执行状态的动态治理组件，支持在节点维度对特定任务实施临时静默 (pause) 与恢复 (resume)，挂载至 Actuator 端点并随 Pod 重启自复位。
_Avoid_: 任务开关, JobController, PauseManager

**SnailRetryTemplate (编程式分布式重试门面)**:
为无法挂载 `@Retryable` / `@AtengSnailRetry` 注解的动态场景（如外部三方客户端调用、动态幂等计算）提供的对称编程式重试门面，支持 `ReentrantLock` / 受管参数化的本地退避重试（`retry.local-max-attempts` / `retry.local-backoff`）、上下文透传、指标采集与手动失败上报。
_Avoid_: 重试工具类, RetryHelper, RetryUtil

**SnailJobAdminTemplate (OpenAPI 编程式管理门面)**:
基于 SnailJob 官方 OpenAPI 构建的强类型生命周期治理门面，支持动态增删改查任务、状态启停（`updateJobStatus` / `enableJob` / `disableJob`）、工作流启停删、即时触发调度与死信任务重放。
_Avoid_: 调度中心SDK, OpenApiClient, SnailAdminClient

**SnailShardingBatchTemplate / SnailMyBatisStreamingTemplate (分片流式批处理门面)**:
专为大数据量静态分片与 MyBatis `Cursor` 流式消费设计的不可变派生友好模板（提供 `withFailFast`、`withProgressLogInterval`、`withMaxConcurrency` 派生视图），基于 Core 受管线程池 `taskExecutor` 与 `Semaphore` 背压透传四维上下文及 `SnailJobContext`，内置 `BatchFailureHandler` 与进度日志门限控制。
_Avoid_: 批量工具, 批处理类, BatchHelper, atengTaskExecutor, ForkJoinPool.commonPool

**TenantJobTemplate (多租户隔离遍历执行模板)**:
对应 `JobTenantMode.ITERATE` 模式的租户沙箱执行门面，支持顺序遍历（`executeForEachTenant`）、结构化统计遍历（`executeForEachTenantWithResult`）与 `Semaphore` 背压并发遍历（`executeForEachTenantParallel`），自动为单租户包裹 `PROPAGATION_REQUIRES_NEW` 独立事务沙箱并支持 `BatchFailureHandler` 失败收集。
_Avoid_: 租户循环工具, MultiTenantRunner, parallelStream

**maxPayloadSize (双向载荷保护防线)**:
由 `ateng.boot.job.snail.max-payload-size`（默认 `1MB`）驱动的双轨容积防线：入口侧在调度切面与 `SnailJobInvocationSupport` 对原始入参 JSON 执行 UTF-8 字节校验并 Fail-Fast 拒绝；出口侧由 `SnailJobLogbackAppender` 对远程镜像日志消息按 UTF-8 字节安全截断（并发配合 `SnailJobContext.MAX_MAP_SUBTASK_SIZE = 500`）。
_Avoid_: 无界入参, 裸体日志截断

**SnailJobHealthIndicator (非阻塞缓存版双擎健康探针)**:
挂载于 `/actuator/health` 的无反射探活组件，基于 `ReentrantLock` 保护的 `health-cache-ttl`（默认 `10s`）缓存与受管并发有界超时（`health-probe-timeout`，默认 `800ms`）探测全部服务端节点；多活节点至少 1 个可达即返回 `UP`（含 `degraded` 与在线/离线清单），全失联返回 `DOWN`，并实时叠加 `draining` 与在途任务数。
_Avoid_: GrpcChannel反射探活, 阻塞无超时Socket检查

**SnailJobLogbackAppender (日志动态镜像追加器)**:
基于 Logback 的零侵入动态日志镜像组件，仅在当前线程处于 SnailJob 调度或重试执行期激活，自动将 SLF4J 标准日志按 `maxPayloadSize` 安全截断并实时同步镜像至 SnailJob 服务端控制台。
_Avoid_: 日志拦截器, 日志打印器, SnailLogFilter

**SnailJobInvocationSupport / SnailJobTestHelper (统一调度内核支撑与脱网调试门面)**:
`SnailJobInvocationSupport` 集中收口 `SnailJobEndpoint` 与 `SnailJobTestHelper` 共享的注解扫描、多参数绑定、`maxPayloadSize` 守卫、`@Valid` 校验与运行时暂停态拦截逻辑；`SnailJobTestHelper` 在不连接真实 SnailJob 独立服务端的脱网环境下，纯内存反射模拟触发任意调度或重试场景并强断言执行结果。
_Avoid_: 测试工具, 调试器, TestRunner, 双处重复反射解析

**SnailJobTriggerController (调度触发领域控制器)**:
样例工程中负责任务手动模拟触发与调度演练的专职 Web 控制器，路由收敛于 `/sample/job-snail/trigger/*`，覆盖系统冷启动预热、参数校验防卫、独立 `@AtengSnailJob` 本地退避重试（`local-retry`）、MapReduce 切片规约、订单对账平账自愈、超时降级与多租户三模（含受管并发 `tenant/parallel`）隔离。
_Avoid_: JobSampleController, 调度接口, TriggerApi, faultInjected有状态开关

**SnailJobRetryController (协同重试领域控制器)**:
样例工程中负责分布式协同重试演练的专职 Web 控制器，路由收敛于 `/sample/job-snail/retry/*`，覆盖声明式 `scene`/`value` 别名支付/退款重试（`pay`、`refund`）、`@AtengSnailRetry` 与 `@Retryable` 混用兼容（`legacy-compat`）、编程式幂等重试、手动失败上报与重试指标巡检。
_Avoid_: RetrySampleController, 重试接口, RetryApi

**SnailJobGovernorController (节点治理领域控制器)**:
样例工程中负责单节点维度任务动态暂停与恢复治理的专职 Web 控制器，路由收敛于 `/sample/job-snail/governor/*`，支持冒号分级感知与已暂停任务清单巡检。
_Avoid_: GovernorApi, 任务开关控制器, PauseController

**SnailJobAdminController (调度中心管理领域控制器)**:
样例工程中通过 `SnailJobAdminTemplate` OpenAPI 编程式门面调用 SnailJob 服务端的专职 Web 控制器，路由收敛于 `/sample/job-snail/admin/*`，覆盖任务注册/更新/查询/触发/状态切换/启停/删除、DAG 工作流批次查询与启停触发删、以及重试任务查询/触发与状态更新运维。
_Avoid_: AdminApi, 调度中心接口, OpenApiController

**SnailJobDeadLetterController (死信切片运维领域控制器)**:
样例工程中负责流式批处理失败切片与重试死信运维的专职 Web 控制器，路由收敛于 `/sample/job-snail/dead-letter/*`，覆盖死信切片明细回查、受控补偿重放与现场清理。
_Avoid_: DeadLetterApi, 死信控制器, FailureController

**UserSyncParam (用户同步调度请求参数 DTO)**:
用户同步调度任务触发端点（`/sample/job-snail/trigger/user-sync`）与 `@AtengSnailJob` 处理器共享绑定的强类型请求入参模型，封装同步批次号、单批数量与部门过滤标识，通过 JSR-303 注解在传输边界执行 Fail-Fast 校验。
_Avoid_: UserMap, SyncMap, UserParamEntity

**BillSettlementRequestDTO (账单结算切片请求 DTO)**:
跨商户月度账单 MapReduce 动态切片触发端点（`/sample/job-snail/trigger/bill-settlement/map`）的强类型请求入参模型，封装待结算账单区间与结算周期标识，约束结算切片边界。
_Avoid_: BillParam, SettlementMap, BillEntity

**OrderPayRetryRequestDTO (订单支付重试请求 DTO)**:
声明式协同重试触发端点（`/sample/job-snail/retry/pay`）的强类型请求入参模型，封装业务订单编号与支付金额校验约束，隔离外部输入与分布式重试执行现场。
_Avoid_: RetryParam, PayMap, OrderEntity

**AdminJobSaveDTO (调度中心任务保存请求 DTO)**:
调度中心编程式任务注册与更新端点（`POST/PUT /sample/job-snail/admin/jobs`）的强类型请求入参模型，封装任务 ID、名称、执行器标识、触发类型、调度表达式、路由策略与描述。
_Avoid_: JobEntity, JobMap, AdminJobParam

**JobTriggerVO (任务触发结果视图对象)**:
调度任务手动触发执行结果的标准对外视图模型，封装任务标识、执行状态、耗时、MDC 链路追踪标识与诊断摘要，遵循 Zero-Entity-Leakage 传输契约。
_Avoid_: TriggerResult, JobResponse, TriggerEntity

**RetryExecutionVO (协同重试执行视图对象)**:
分布式协同重试演练执行结果的标准对外视图模型，封装业务订单号、重试场景、幂等键、尝试次数与执行诊断消息。
_Avoid_: RetryResult, RetryResponse, RetryDTO

**RetryStatsVO (协同重试统计视图对象)**:
分布式协同重试统计端点（`GET /sample/job-snail/retry/stats`）的标准对外视图模型，封装重试场景标识与累计尝试执行次数，消除控制器边界裸 `Map` 返回。
_Avoid_: StatsMap, RetryStatsEntity, RetryMap

**GovernorStatusVO (节点运行时治理状态视图对象)**:
单节点任务治理状态的标准对外视图模型，封装目标任务标识、操作类型、执行状态与节点当前暂停任务总数。
_Avoid_: GovernorResult, PauseResponse, GovernorDTO

**DeadLetterBatchVO (死信批次切片视图对象)**:
流式批处理失败切片记录的标准对外视图模型，封装批次起止游标、分片索引、失败条数与因果异常说明。
_Avoid_: DeadLetterItem, FailedBatchVO, ErrorBatch
