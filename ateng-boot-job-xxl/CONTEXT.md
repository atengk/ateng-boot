# XXL-Job 分布式任务调度与生产加固底座

面向企业级微服务分布式任务调度、大数据分片批处理与高可用运维治理的基础设施，基于官方 `com.xuxueli:xxl-job-core:3.5.0`（原生 Spring Boot 3 与 JDK 17 基线）深度加固，提供原生无侵入增强与独立高阶声明式注解 `@AtengXxlJob`（支持单注解独立注册与双标兼容、方法级本地硬超时、本地退避重试、多租户隔离与告警抑制）、全链路 `ReentrantLock` 虚拟线程防 Pinning、四维全链路上下文透传（`AsyncContext` / MDC `traceId` / 多租户隔离）、SLF4J 日志动态镜像无感推送（`XxlJobLogbackAppender`）、本地硬看门狗超时中断熔断与两级降级补偿（同 Bean 补偿 + `JobTimeoutFallbackHandler` 全局 SPI）、任务切面责任链拦截器 SPI（`XxlJobInterceptor`）、节点级任务运行时治理总线（`JobRuntimeGovernor`）、强类型 DTO 入参与 JSR-303 防卫、大数据分片流式批处理模板（`XxlShardingBatchTemplate` / `XxlMyBatisStreamingTemplate`，含 `FailedBatch` 死信切片收集、`BatchFailureHandler` SPI 与进度打卡）、多租户受管并发遍历（`TenantJobTemplate.executeForEachTenantParallel` 信号量背压并发）、K8s 停机三阶平滑排空状态机、执行器端口防踩踏自愈与 CIDR 防火墙、三阶告警防风暴聚合自愈、编程式管理门面 `XxlJobAdminTemplate`，以及脱网调试门面 `XxlJobTestHelper` 与 `@Profile("!local")` 100% 离线自洽双模沙箱。

## Language

### 核心调度与执行器治理

**XxlJob (分布式受管任务)**:
由 `@XxlJob` 原生标记或由 `@AtengXxlJob` 独立高阶声明的分布式受管任务单元，由中心化 XXL-JOB Admin 触发调度并在执行器集群上分发执行。
_Avoid_: 定时器, 后台线程, CronTask, 定时作业, 离线任务

**AtengXxlJob (高阶声明式调度注解)**:
兼容并扩展原生 `@XxlJob` 契约的独立高阶注解，支持单注解直接注册 JobHandler，并显式声明方法级本地硬超时阈值（`timeout`）、超时降级补偿方法（`fallback`）、方法级本地退避重试（`retryCount` / `retryInterval`）、多租户隔离模式（`tenantMode`）、告警抑制策略（`alertEnabled` / `alertSuppressThreshold`）与强类型参数反序列化。
_Avoid_: 增强注解, 自定义Job, JobAnnotation

**AtengXxlJobSpringExecutor (生产加固型受管执行器)**:
接管任务扫描注册（同时识别 `@XxlJob` 与独立 `@AtengXxlJob`）、容器网卡 IP 智能嗅探、端口冲突顺延自愈、受管调度线程接管与 K8s 三阶平滑停机排空状态机的核心执行器组件。
_Avoid_: 执行器包装类, CustomExecutor, JobRunner, SpringExecutorWrapper

**AtengEmbedServer (内嵌安全通信服务端)**:
基于 Netty 构建的执行器入站 HTTP 通信服务端，在 Pipeline 前端集成停机排空拒收守卫、CIDR 白名单防火墙与 AccessToken 快速失败鉴权。
_Avoid_: 内置服务器, NettyServer, HttpReceiver, RpcServer

**XxlJobFirewallInboundHandler (入站安全防火墙处理器)**:
挂载于执行器 Netty 通信管道最前端的安全防卫处理器，负责执行器排空期新任务拒收、基于 Core `CidrMatcher` 的来源 IP CIDR 网段白名单校验与访问令牌前置核验。
_Avoid_: IP过滤器, 鉴权拦截器, NettyFilter, TokenHandler, job.xxl.security.CidrMatcher

**XxlJobContextAspect (四维上下文与韧性调度切面)**:
环绕拦截所有受管分布式任务的核心调度切面，统一编排节点静默判定、32 位无锁 MDC `traceId` 注入、操作人与多租户上下文挂载、强类型参数绑定与 JSR-303 校验、切面拦截器责任链、本地硬看门狗、方法级退避重试、两级超时降级与三阶告警度量。
_Avoid_: 任务AOP, 拦截切面, JobAspect, ProxyHandler

### 上下文、拦截器与运行时治理

**ShardingContext (分片广播上下文)**:
传递当前分布式调度节点分片元数据的强类型载荷模型，包含当前分片索引序号 `shardIndex`（从 0 开始）、集群总分片数 `shardTotal` 及非负取模路由匹配原语。
_Avoid_: 分片参数, 分片对象, ShardInfo, ShardMeta

**JobTenantMode (任务租户隔离策略)**:
标识定时任务在执行期间的多租户隔离边界策略：`IGNORE`（平台级全局扫描，忽略租户限制）、`PARAM`（从调度参数或强类型 DTO 中提取 `tenantId` 独立挂载）、`ITERATE`（租户级沙箱轮询，依次或受管并发隔离执行）。
_Avoid_: 租户类型, 隔离级别, TenantType, MultiTenantPolicy

**XxlJobInterceptor (任务执行切面拦截器 SPI)**:
为分布式受管任务提供前置准入判定（`preHandle`）、执行后置处理（`postHandle`）与终态逆序清理（`afterCompletion`）的三阶段切面责任链扩展点，支持节点金丝雀灰度跳过（返回 `false` 自动上报 200 SUCCESS 防告警风暴）与全局执行审计。
_Avoid_: 任务过滤器, JobFilter, 任务钩子, JobAspectSPI

**XxlJobExecutionContext (任务切面执行上下文)**:
在切面拦截器责任链各阶段间流转的强类型上下文元数据模型，内聚 `jobName`（`jobHandler`）、`jobId`、`logId`、`traceId`、`tenantId`、`shardingContext`、`rawParam`、解析后实参、目标方法与线程安全扩展属性字典。
_Avoid_: 拦截参数, 切面变量, AspectContext, FilterParam

**JobRuntimeGovernor (节点级任务运行时治理总线)**:
负责单个微服务节点内部受管任务执行状态动态治理的纯内存管控组件，支持在单节点维度对特定任务实施临时静默（`pause`）与恢复（`resume`）且随 Pod 重启自复位，防止局部故障扩散。
_Avoid_: 任务开关, JobController, 节点控制器, JobToggle

**XxlJobContextSnapshotProvider (调度上下文异步传播提供者)**:
实现 Core `AsyncContextSnapshotProvider` SPI 的上下文桥接器，在受管异步线程池派发子任务时自动捕获、挂载并在终态强制清理 `XxlJobContext`，根治官方 `InheritableThreadLocal` 在池化复用下的浅拷贝污染。
_Avoid_: 线程上下文复制器, ThreadLocalHelper, ContextCopier

### 看门狗、告警自愈与可观测性

**LocalWatchdog (本地硬超时看门狗引擎)**:
基于独立时间轮监控受管任务执行耗时的硬超时守护组件，超限时主动向工作线程发起 `Thread.interrupt()` 强中断、记录控制台红字日志并广播 `JobTimeoutEvent`，防御僵尸线程耗尽执行器资源。
_Avoid_: 超时定时器, TimeoutTimer, ThreadKiller, KillSwitch

**JobExecutionMetadata (任务超时执行元数据)**:
封装单次任务调度的 `jobId`、`jobName`、`logId`、`traceId` 与 `tenantId` 标识快照的不可变元数据载体，供本地看门狗与两级超时降级处理器传递上下文。
_Avoid_: 任务信息, 调度摘要, JobMeta, TaskHeader

**JobTimeoutFallbackHandler (任务超时降级处理器 SPI)**:
当分布式受管任务被本地硬看门狗判定为超时强行中断，且未声明同 Bean 就地 `fallback` 方法时，由受管异步线程池触发的全局补偿降级扩展点，负责外部锁释放、流水冲正或状态回滚。
_Avoid_: 超时处理器, TimeoutHandler, 降级工具, FallbackHelper

**JobTimeoutEvent (任务硬超时领域事件)**:
当受管任务触发本地硬看门狗超时强中断时向 Spring 容器广播的标准领域事件，携带任务标识、超时阈值、实际耗时、工作线程名与四维追踪上下文。
_Avoid_: 超时消息, TimeoutNotice, OvertimeSignal

**XxlJobAlertAggregator (三阶告警风暴抑制聚合器)**:
执行器内部负责任务调度失败异常聚合收敛的状态机组件，实现首发失败立即告警（`INITIAL_FAILURE`）、抑制窗口期内收敛为周期摘要卡片（`FAILURE_SUMMARY`）、平账成功后自动发送绿色自愈通知（`RECOVERY`）的闭环流转。
_Avoid_: 告警过滤器, 报警器, AlertFilter, ErrorAlerter

**XxlJobAlertEvent (任务三阶告警与自愈领域事件)**:
由告警聚合器在首发故障、持续失败摘要与故障自愈三个生命周期节点广播的 Spring 领域事件，支撑下游监控中心与群机器人卡片联动。
_Avoid_: 报错事件, AlarmMessage, ErrorEvent

**XxlJobLogbackAppender (日志动态镜像追加器)**:
基于 Logback 的零侵入动态日志镜像组件，仅在当前线程处于 XXL-JOB 调度生命周期内激活，自动将 SLF4J 标准日志（含 MDC `traceId` 与异常栈）实时镜像写入当前调度分片日志文件。
_Avoid_: 日志拦截器, 日志打印器, LogFilter, LogForwarder

**AtengJobLogFileCleanHelper (防崩历史日志清理器)**:
基于受管定时执行器与严格日期目录正则校验的历史调度日志淘汰组件，自动过滤操作系统隐性文件并隔离文件句柄占用异常，防止后台清理崩溃。
_Avoid_: 日志删除线程, FileCleaner, LogDeleter

**XxlJobEndpoint (Actuator 运维管控端点)**:
暴露于 `/actuator/xxljobs` 的 Spring Boot Actuator 专职运维端点，支持受管任务全景清单巡检、单 Handler 反射元数据诊断、单节点动态静默/恢复以及在线手动触发。
_Avoid_: 运维控制器, OpsApi, ActuatorController

**XxlJobHealthIndicator (执行器健康探针)**:
挂载于 `/actuator/health` 的存活与就绪探针，动态感知执行器启停、静默旁路模式以及自愈后实际 Netty 端口的物理监听连通性。
_Avoid_: 心跳检查器, PingChecker, PortProbe

**XxlJobMetricsBinder (Micrometer 调度度量绑定器)**:
采集并导出 `ateng.job.xxl.execution` Timer 指标（按 `handler`、`status`、`tenant` 低基数标签统计吞吐量、失败数与 P50/P95/P99 分位耗时）的云原生监控组件。
_Avoid_: 性能计数器, MetricsCollector, StatReporter

### 分片流式批处理与多租户并发

**XxlShardingBatchTemplate (分片流式批处理门面)**:
专为大数据量分片广播场景设计的无状态流式批处理模板，内聚游标步进拉取、分片非负取模自动过滤、基于信号量背压的受管并发消费、子线程四维上下文与日志句柄透传、实时进度打卡及有界死信收集。
_Avoid_: 批量工具, 分片批处理类, BatchHelper, ShardingUtil

**XxlMyBatisStreamingTemplate (MyBatis-Plus 流式游标批处理门面)**:
继承 `XxlShardingBatchTemplate` 并对接 MyBatis-Plus `StreamingQueryTemplate` 与原生 `Cursor<T>` 的持久化流式批处理模板，支持数据库游标单线程顺序与受管多线程背压并发消费。
_Avoid_: 游标工具, MyBatisBatchUtil, CursorHelper

**FailedBatch (失败切片死信模型)**:
在大数据量分片流式批处理中发生单批消费异常时封装的不可变错误切片模型，携带失败实体明细列表 `items`、批次序号 `batchIndex`、推进游标 `cursorId` 与底层引发异常 `cause`。
_Avoid_: 失败对象, 异常批次, ErrorBatch, DeadBatch

**BatchFailureHandler (批处理失败处理回调 SPI)**:
在大数据量分片流式批处理遇到批次处理异常时的函数式受管回调扩展点（兼容 `Consumer<FailedBatch<T>>`），支持方法级显式回调与全局 Spring Bean 默认兜底，负责失败切片的死信落库或补偿派发。
_Avoid_: 错误回调, ErrorHandler, 失败监听器, BatchErrorListener

**BatchExecutionResult (批处理有界结果统计模型)**:
封装分片流式批处理或租户遍历的总读取条数、成功数、失败数、总批次数、执行耗时与有界失败明细集合（刚性上限 `MAX_FAILED_ITEMS = 1000` 自动截断防堆内存 OOM）的不可变汇总结果。
_Avoid_: 批处理返回值, BatchSummary, CountResult

**TenantJobTemplate (多租户沙箱遍历门面)**:
面向 `JobTenantMode.ITERATE` 模式的租户级批量调度模板，提供单线程顺序沙箱遍历与基于信号量背压的受管并发沙箱遍历（`executeForEachTenantParallel`），确保租户间上下文与数据绝对隔离。
_Avoid_: 租户循环器, TenantRunner, MultiTenantHelper

**TenantListProvider (有效租户发现 SPI)**:
在多租户遍历调度模式下向 `TenantJobTemplate` 提供当前平台全部有效租户标识列表的扩展点接口。
_Avoid_: 租户查询服务, TenantDao, TenantFetcher

### OpenAPI 编程式管控、脱网调试与样例领域契约

**XxlJobAdminTemplate (调度中心编程式管理门面)**:
依托 XXL-JOB 3.x 官方 `AdminJobBiz` OpenAPI 构建的无状态客户端门面，内置多调度中心节点自动故障转移（Failover），提供任务的动态注册（`addJob` / `addJobAndGetId`）、配置更新（`updateJob`）、启停（`startJob` / `stopJob`）、即时触发（`triggerJob`）与移除（`removeJob`）对称操作。
_Avoid_: 调度中心客户端, AdminClient, JobManager

**XxlJobTestHelper (脱网调试门面)**:
面向本地开发与自动化单测的无锁脱网调度驱动器，支持在不启动外部 XXL-JOB Admin 的前提下，直接传入字符串或强类型 POJO/DTO 模拟触发任意受管任务并穿透完整切面防线。
_Avoid_: 测试工具, 调试器, JobMockRunner, TestTrigger

**XxlJobTestResult (脱网调试执行诊断模型)**:
由 `XxlJobTestHelper` 触发脱网调度后返回的不可变诊断结果记录，封装执行成功标志、调度中心 `handleCode`、`handleMsg`、执行耗时与底层异常对象。
_Avoid_: 运行结果, 测试返回值, TestResponse, ExecutionData

**XxlJobTriggerController (调度触发与韧性演练领域控制器)**:
样例工程中暴露于 `/sample/job-xxl/trigger/*` 的专职 Web 控制器，提供系统预热巡检、强类型 DTO 用户同步、大数据分片广播与受管并发批处理、订单对账三阶告警自愈、冷数据归档看门狗超时降级、方法级本地退避重试、灰度切面拦截与多租户全模态遍历的 HTTP 体验端点。
_Avoid_: 触发接口, SampleController, JobTestApi

**XxlJobGovernorController (节点运行时治理领域控制器)**:
样例工程中暴露于 `/sample/job-xxl/governor/*` 的专职 Web 控制器，提供单节点维度受管任务在线静默（`pause`）、恢复（`resume`）与已静默任务清单巡检端点。
_Avoid_: 开关接口, PauseController, NodeApi

**XxlJobAdminController (调度中心 OpenAPI 运维领域控制器)**:
样例工程中暴露于 `/sample/job-xxl/admin/*` 的专职 Web 控制器，基于 `XxlJobAdminTemplate` 提供调度中心任务的动态新增、配置更新、启动、停止、即时触发与删除全生命周期 REST 端点。
_Avoid_: 管理后台接口, OpenApiController, AdminRest

**XxlJobDeadLetterController (分片死信切片运维领域控制器)**:
样例工程中暴露于 `/sample/job-xxl/dead-letter/*` 的专职 Web 控制器，提供分片流式批处理捕获的 `FailedBatch` 死信切片列表回查、受控补偿重放与现场清理端点。
_Avoid_: 死信接口, ErrorBatchController, FailureApi

**JobTriggerVO (任务触发诊断结果视图对象)**:
向 Web 传输边界交付的强类型任务执行结果视图模型，封装成功状态、响应码、消息摘要、执行耗时与异常原因，严守 Zero-Entity-Leakage 契约。
_Avoid_: 触发返回值, JobResultEntity, TriggerResponse

**DeadLetterBatchVO (死信切片视图对象)**:
向 Web 传输边界交付的分片批处理失败切片视图模型，封装失败批次序号、推进游标 ID、失败记录条数、异常原因与业务单号列表。
_Avoid_: 死信实体, FailedBatchEntity, ErrorVO

**UserSyncParam (用户同步调度请求参数 DTO)**:
用户同步调度触发端点与 `@AtengXxlJob` 处理器共享绑定的强类型请求入参模型，封装同步批次号、单批数量与租户标识，在传输边界与调度切面执行双防 JSR-303 Fail-Fast 校验。
_Avoid_: 同步参数Map, UserMap, SyncEntity

**AdminJobAddRequestDTO (调度中心任务注册请求 DTO)**:
调度中心编程式任务新增端点（`POST /sample/job-xxl/admin/jobs`）的强类型入参契约，封装执行器分组、任务名称、Handler 标识、调度类型与 Cron 表达式并执行 JSR-303 校验。
_Avoid_: 新增任务实体, JobAddMap, AddJobParam

**AdminJobUpdateRequestDTO (调度中心任务更新请求 DTO)**:
调度中心编程式任务更新端点（`PUT /sample/job-xxl/admin/jobs`）的强类型入参契约，封装目标任务 ID、更新后的任务名称、Handler 标识、调度类型与 Cron 表达式并执行 JSR-303 校验。
_Avoid_: 修改任务实体, JobUpdateMap, UpdateJobParam
