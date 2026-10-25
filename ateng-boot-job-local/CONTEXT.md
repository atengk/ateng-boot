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
