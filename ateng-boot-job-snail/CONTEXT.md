# SnailJob 分布式任务调度与分布式重试加固底座

面向企业级微服务分布式任务调度、复杂工作流（DAG）、Map/MapReduce 动态分布式切片计算、分布式业务异常重试（Local / Remote）与死信治理的基础设施，基于官方 `com.aizuda:snail-job-client-starter:2.0.2`（原生 Spring Boot 3 与 JDK 17 基线）深度加固，提供原生无侵入增强与高阶声明式注解 `@AtengSnailJob`、分布式重试增强 `@AtengSnailRetry`、四维全链路上下文透传与远程重试保真还原（`AsyncContext` / MDC `traceId` / 租户模式 / 操作人）、SLF4J 动态日志镜像无感推送（`SnailJobLogbackAppender`）、本地硬看门狗超时中断熔断、强类型 DTO 入参与 JSR-303 防卫、大数据分片流式批处理模板（`SnailShardingBatchTemplate`）、原生 MapReduce 流式切片门面、编程式重试门面（`SnailRetryTemplate`）、OpenAPI 编程式管理门面（`SnailJobAdminTemplate`）、三阶告警防风暴聚合自愈与死信领域事件直通通道、K8s 停机三阶平滑排空状态机，以及免服务端本地调试端点与 100% 离线自洽单测沙箱。

## Language

**SnailJob (分布式双擎受管单元)**:
由 `@JobExecutor` 或 `@Retryable` 声明的分布式受管单元，支持常规定时调度、工作流（DAG）、Map/MapReduce 动态切片计算，以及本地/远程分布式异常重试。
_Avoid_: 定时器, 后台线程, 重试切面, EasyRetryJob

**AtengSnailJob (高阶声明式调度注解)**:
扩展原生 `@JobExecutor` 契约的高阶注解，支持显式声明方法级本地硬超时阈值（`timeout`）、多租户隔离模式（`tenantMode`）、告警抑制策略与强类型入参 DTO 自动反序列化。
_Avoid_: SnailJob增强注解, 自定义Job, JobAnnotation

**AtengSnailRetry (高阶声明式重试注解)**:
扩展原生 `@Retryable` 契约的高阶注解，支持声明 SpEL 业务幂等键（`idempotentSpel`）、死信通知策略与四维上下文快照现场绑定。
_Avoid_: 重试增强, 业务重试, RetryAnnotation

**JobTenantMode (任务租户隔离策略)**:
标识任务在调度期间的多租户隔离边界策略：`IGNORE`（平台级全局扫描，忽略租户限制）、`PARAM`（从调度参数中提取 `tenantId` 独立挂载）、`ITERATE`（租户级沙箱轮询，依次独立事务执行）。
_Avoid_: 租户级别, 隔离模式, TenantPolicy

**SnailRetryTemplate (编程式分布式重试门面)**:
为无法挂载 `@Retryable` 注解的动态场景（如外部三方客户端调用、动态幂等计算）提供的对称编程式重试门面，支持上下文透传、指标采集与手动失败上报。
_Avoid_: 重试工具类, RetryHelper, RetryUtil

**SnailJobAdminTemplate (OpenAPI 编程式管理门面)**:
基于 SnailJob 官方 OpenAPI 构建的强类型生命周期治理门面，支持动态增删改查任务、工作流启停、即时触发调度与死信任务重放。
_Avoid_: 调度中心SDK, OpenApiClient, SnailAdminClient

**SnailShardingBatchTemplate (分片流式批处理门面)**:
专为大数据量静态分片场景设计的流式批处理模板，内聚子线程四维上下文透传、游标步进流式查询与堆内存防 OOM 刚性熔断。
_Avoid_: 批量工具, 批处理类, BatchHelper

**SnailJobLogbackAppender (日志动态镜像追加器)**:
基于 Logback 的零侵入动态日志镜像组件，仅在当前线程处于 SnailJob 调度或重试执行期激活，自动将 SLF4J 标准日志实时同步镜像至 SnailJob 服务端控制台。
_Avoid_: 日志拦截器, 日志打印器, SnailLogFilter

**SnailJobTestHelper (脱网调试门面)**:
面向本地开发与自动化单测的脱网调度驱动器，允许在不连接真实 SnailJob 独立服务端的脱网环境下，纯内存反射模拟触发任意 JobExecutor 或 Retry 场景并强断言执行结果。
_Avoid_: 测试工具, 调试器, TestRunner
