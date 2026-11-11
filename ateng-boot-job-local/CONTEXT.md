# LocalJob 单机定时任务与轻量调度底座

面向单机定时调度与轻量分布式集群防重的生产级调度基础设施，坚持纯原生无重依赖哲学，基于 Spring `ThreadPoolTaskScheduler` 与受管异步 Worker 线程池提供声明式 `@LocalJob` 注解与对称编程式门面 `LocalJobTemplate`、四阶重叠并发防卫策略（`OverlappingStrategy`）、硬超时熔断与指数退避重试、32 位无锁 MDC `traceId` 全链路追踪、两级配置加载与有界环形淘汰防 OOM（`JobLogStorageProvider`）、防死锁租约轻量集群互斥锁（`JobClusterLockProvider`）、`SmartLifecycle` 优雅停机排空、`/actuator/localjobs` 运维管控端点、Spring 领域事件总线及群机器人告警桥接设施。

## Language

**LocalJob (单机受管定时任务)**:
由 `@LocalJob` 声明或由 `LocalJobTemplate` 动态注册的受管定时调度任务单元，具备独立的生命周期状态、并发防卫与监控审计机制。
_Avoid_: 定时器, 后台线程, 轮询器, Timer, WorkerThread, CronTask

**LocalJobTemplate (定时任务操作门面)**:
为应用提供对受管定时任务动态增删、启停、周期变更、手动触发与状态巡检的统一对称操作门面。
_Avoid_: 任务管理器, 调度中心, JobManager, SchedulerCenter

**ScheduledJobTask (受管调度任务实例)**:
单机任务在运行时的受管执行包装类，内聚重叠并发锁、Worker 执行池调度、超时强中断、指数退避重试、MDC 注入与领域事件广播。
_Avoid_: 运行线程, 任务封装, Runner, TaskWrapper

**JobContext (任务执行上下文)**:
注入到任务执行方法内部的运行时上下文句柄，提供当前调度的 `traceId`、`jobName`、`fireTime`、自定义入参及结构化日志追加能力（`log(...)`）。
_Avoid_: 全局环境, 线程变量, TaskEnv, ThreadLocalContext

**JobResult (任务执行结果产物)**:
承载任务执行成功/失败状态、人读业务摘要信息与业务产物载荷数据的强类型执行结果模型。
_Avoid_: 响应对象, 结果报文, ResultVO, TaskOutput

**JobExecutionRecord (任务执行审计记录)**:
记录单次任务调度的全局唯一记录 ID、链路追踪 `traceId`、触发方式、启动与完成时间、执行耗时、最终状态、重试次数及执行日志列表的历史模型。
_Avoid_: 日志实体, 执行历史, HistoryLog, TaskRecord

**JobStatus (任务生命周期状态)**:
标识定时任务在调度运行时的离散状态：`RUNNING`（正常调度运行中）、`PAUSED`（已暂停周期调度）、`STOPPED`（已停止或注销）。
_Avoid_: 任务标志, 运行标记, StateFlag, RunStatus

**TriggerType (任务触发方式)**:
标识任务单次执行的驱动源：`CRON`（标准 Cron 表达式时钟触发）、`FIXED_RATE`（固定频率间隔触发）、`FIXED_DELAY`（固定延迟间隔触发）、`MANUAL`（通过 API/Actuator 手动立即触发）。
_Avoid_: 调度模式, 触发源, FireMode, ScheduleType

**OverlappingStrategy (重叠并发防卫策略)**:
当前序任务尚未结束而后序触发周期已到达时，调度引擎所执行的并发保护策略：`DISCARD`（防雪崩跳过）、`QUEUE`（平滑排队执行）、`CONCURRENT`（并行并发执行）、`INTERRUPT`（打断前序执行）。
_Avoid_: 并发控制, 冲突策略, ConcurrencyMode, ConflictPolicy

**JobClusterLockProvider (轻量集群互斥锁 SPI)**:
在多实例或多 Pod 部署环境下，协调同一任务仅由单一节点获得执行权的互斥锁抽象，支持抢锁安全跳过与租约自动防死锁。
_Avoid_: 分布式锁, Redis锁, DistributedLock, RedissonLock

**JobLockHandle (集群锁持有句柄)**:
成功获取集群锁后返回的生命周期凭证，持有者在任务完成后必须显式关闭以释放锁。
_Avoid_: 锁令牌, 钥匙, LockToken, KeyHandle

**JobMetadataRepository (任务元数据仓库 SPI)**:
提供任务静态与动态元数据持久化读写的扩展点，支持数据库动态配置仲裁覆盖代码注解静态配置。
_Avoid_: 数据库仓库, 配置表, TaskDAO, ConfigRepository

**JobLogStorageProvider (任务日志存储 SPI)**:
提供执行审计日志暂存与检索的扩展点，默认通过内存有界队列实现 FIFO 环形淘汰，支持最大保留条数与时间窗口滚动清理防堆 OOM。
_Avoid_: 日志库, 审计表, LogDAO, HistoryStorage

**JobAlertHandler (任务告警处理器 SPI)**:
当任务发生执行异常失败或硬超时熔断时，接收结构化告警上下文并向外部（控制台日志、企业微信、钉钉、飞书群机器人）转发的扩展点。
_Avoid_: 告警机, 报错监听, AlertSender, ErrorDispatcher

**LocalJobEndpoint (Actuator 运维管控端点)**:
暴露于 `/actuator/localjobs` 的 Spring Boot Actuator 运维端点，提供标准化 HTTP 接口进行全量巡检、单任务详情查询、手动即时触发、暂停与恢复。
_Avoid_: 运维接口, 控制台API, OpsController, AdminAPI

**JobTenantMode (多租户调度隔离模态)**:
定义受管定时任务在 SaaS 多租户架构下的执行隔离策略：`IGNORE`（忽略租户全局运行）、`PARAM`（指定入参租户运行）、`ITERATE`（全租户轮询隔离运行）。
_Avoid_: 租户类型, 隔离级别, TenantType, TenantLevel

**TenantProvider (有效租户发现 SPI)**:
当任务配置为 `ITERATE` 全租户轮询模式时，向调度引擎提供当前系统全部有效租户 ID 列表的扩展点接口。
_Avoid_: 租户仓储, 租户服务, TenantService, TenantRepository

**AntiStormJobAlertAggregator (三阶告警防风暴聚合器)**:
高频任务持续失败时，实施“首次即刻通知、滑动静默窗口期内聚合错误计数、自愈闭环发送恢复通知”的三阶告警抑制与自愈管理设施。
_Avoid_: 告警拦截器, 限流器, AlertFilter, AlertLimiter

**RedisJobClusterLockProvider (Redis 轻量集群互斥锁提供者)**:
基于 Spring 原生 `StringRedisTemplate` 与原子 Lua 脚本实现的低延迟轻量集群互斥锁，支持租约超时自动防死锁与属主防误删防卫。
_Avoid_: Redisson锁, Redis加锁器, RedisLocker, LockService

**LocalJobMetricsBinder (Micrometer 云原生指标采集器)**:
将受管定时任务的执行次数、成功失败状态、耗时直方图（P95/P99）、并发丢弃与活跃执行等指标桥接导出至 Micrometer 与 Prometheus 的监控采集设施。
_Avoid_: 监控打点器, 指标统计, MetricsCollector, JobTracker

**LocalJobScheduleController (任务调度触发控制器)**:
暴露于 `/sample/job-local/schedule` 的领域控制器，负责受管定时任务的手动即时触发、带参归档校验触发、SaaS 多租户轮询触发与集群互斥抢锁触发。
_Avoid_: 调度接口, 触发器, TriggerController, FireAPI

**LocalJobManageController (任务生命周期管控控制器)**:
暴露于 `/sample/job-local/manage` 的领域控制器，负责受管定时任务的暂停、恢复、Cron/频率/延迟周期动态重置、运行时编程式注册与注销移除。
_Avoid_: 管理接口, 控制器API, AdminController, TaskManagerAPI

**LocalJobAuditController (任务状态巡检与审计控制器)**:
暴露于 `/sample/job-local/audit` 的领域控制器，负责全量受管任务清单巡检、单任务运行详情查询、执行历史审计日志检索、Cron 未来时序预演与过期日志滚动清理。
_Avoid_: 审计接口, 日志控制器, LogController, QueryAPI

**JobDynamicRegisterRequest (动态任务注册请求契约)**:
承载运行时编程式动态注入新定时任务的入参模型，内聚任务名、描述、Cron 表达式、固定频率或固定延迟等参数并提供 JSR-303 Fail-Fast 校验。
_Avoid_: 注册参数, 新建任务DTO, RegisterParam, NewJobDTO

**JobCronUpdateRequest (Cron 表达式动态更新请求契约)**:
承载运行时动态修改指定受管任务 Cron 调度周期的入参模型，支持标准 6 位 Cron 校验与 Fail-Fast 防卫。
_Avoid_: 修改Cron参数, 更新表达式DTO, CronParam, UpdateCronDTO

**JobRateUpdateRequest (固定频率周期更新请求契约)**:
承载运行时动态调整固定频率 (FixedRate) 任务调度周期的入参模型，支持 Spring 格式时间间隔文本校验。
_Avoid_: 频率参数, 间隔DTO, RateParam, UpdateRateDTO

**JobDelayUpdateRequest (固定延迟周期更新请求契约)**:
承载运行时动态调整固定延迟 (FixedDelay) 任务调度周期的入参模型，支持 Spring 格式时间间隔文本校验。
_Avoid_: 延迟参数, 延后DTO, DelayParam, UpdateDelayDTO

**ArchivingJobParam (带参归档任务入参契约)**:
承载业务归档任务强类型参数的传输对象，支持业务表名与保留天数校验，与底层领域实体绝对解耦。
_Avoid_: 归档参数, 业务入参, ArchivingDTO, TableParam

**CronPreviewRequest (Cron 表达式预演请求契约)**:
承载 Cron 表达式合法性校验与未来时钟触发点计算的入参模型，包含待预演 Cron 文本与预演次数。
_Avoid_: 预演参数, 校验入参, PreviewParam, CheckCronDTO

**JobResultVO (任务执行结果展示模型)**:
向 Web 边界交付的强类型任务单次执行结果视图对象，内聚成功状态、业务摘要文案与执行耗时，彻底杜绝内部实体泄漏。
_Avoid_: 结果VO, 执行结果对象, ResultView, JobResponse

**JobInfoVO (受管任务元数据与状态展示模型)**:
向 Web 边界交付的受管定时任务全生命周期大盘概览视图对象，包含任务名、状态、触发类型、累计执行指标与最近结果摘要。
_Avoid_: 任务VO, 详情模型, TaskView, JobSummaryVO

**JobExecutionRecordVO (任务执行审计明细展示模型)**:
向 Web 边界交付的单次调度审计日志视图对象，包含记录 ID、MDC traceId、触发源、耗时、重试次数及有界审计日志列表。
_Avoid_: 审计VO, 日志视图, RecordView, AuditDetailVO

**CronPreviewVO (Cron 预演时钟点展示模型)**:
向 Web 边界交付的 Cron 校验结果与未来时钟触发序列视图对象，包含表达式有效性标识与未来 Instant 时间列表。
_Avoid_: 预演VO, 计算结果模型, PreviewResult, CronTimesVO

**DualModeScheduleEnvironment (双模联调环境体系)**:
兼顾 CI 离线自洽与真实外部中间件直连的测试运行模态：默认使用 LOCAL 本地防重锁或内存环境免外部依赖，本地联调通过 application-local.yml 激活真实 Redis 轻量集群锁。
_Avoid_: 双环境, 本地远程模式, TwoModes, DualProfile
