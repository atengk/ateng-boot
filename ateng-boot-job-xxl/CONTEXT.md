# XXL-Job 分布式任务调度与生产加固底座

面向企业级微服务分布式任务调度、大数据分片批处理与高可用运维治理的基础设施，基于官方 `com.xuxueli:xxl-job-core:3.5.0`（原生 Spring Boot 3 与 JDK 17 基线）深度加固，提供原生无侵入增强与高阶声明式注解 `@AtengXxlJob`、四维全链路上下文透传（`AsyncContext` / MDC `traceId` / 多租户隔离）、SLF4J 日志动态镜像无感推送（`XxlJobLogbackAppender`）、本地硬看门狗超时中断熔断、强类型 DTO 入参与 JSR-303 防卫、大数据分片流式批处理模板（`XxlShardingBatchTemplate`）、K8s 停机三阶平滑排空状态机、执行器端口防踩踏自愈与 CIDR 防火墙、三阶告警防风暴聚合自愈，以及免调度中心本地调试端点与 100% 离线自洽单测沙箱。

## Language

**XxlJob (分布式受管任务)**:
由 `@XxlJob` 原生标记或由 `@AtengXxlJob` 高阶声明的分布式受管任务单元，由中心化 XXL-JOB Admin 触发调度并在执行器集群上分发执行。
_Avoid_: 定时器, 后台线程, CronTask, 定时作业, 离线任务

**AtengXxlJob (高阶声明式调度注解)**:
扩展原生 `@XxlJob` 契约的高阶注解，支持显式声明方法级本地硬超时阈值（`timeout`）、多租户隔离模式（`tenantMode`）、告警抑制策略（`alert`）与强类型参数反序列化。
_Avoid_: 增强注解, 自定义Job, JobAnnotation

**ShardingContext (分片广播上下文)**:
传递当前分布式调度节点分片元数据的强类型载荷模型，包含当前分片索引序号 `shardIndex`（从 0 开始）与集群总分片数 `shardTotal`。
_Avoid_: 分片参数, 分片对象, ShardInfo, ShardMeta

**JobTenantMode (任务租户隔离策略)**:
标识定时任务在执行期间的多租户隔离边界策略：`IGNORE`（平台级全局扫描，忽略租户限制）、`PARAM`（从调度参数中提取 `tenantId` 独立挂载）、`ITERATE`（租户级沙箱轮询，依次独立事务执行）。
_Avoid_: 租户类型, 隔离级别, TenantType, MultiTenantPolicy

**XxlShardingBatchTemplate (分片流式批处理门面)**:
专为大数据量分片广播场景设计的流式批处理模板，内聚子线程四维上下文透传、SLF4J 日志镜像、游标步进流式查询与内存防 OOM 刚性熔断。
_Avoid_: 批量工具, 分片批处理类, BatchHelper, ShardingUtil

**XxlJobLogbackAppender (日志动态镜像追加器)**:
基于 Logback 的零侵入动态日志镜像组件，仅在当前线程处于 XXL-JOB 调度执行期激活，自动将 SLF4J 标准日志实时同步镜像至 XXL-JOB 调度中心控制台。
_Avoid_: 日志拦截器, 日志打印器, LogFilter, LogForwarder

**XxlJobAdminTemplate (调度中心编程式门面)**:
依托 XXL-JOB 3.x 官方 `AdminJobBiz` OpenAPI 构建的编程式客户端门面，支持在微服务运行时动态向 Admin 注册任务、更新 Cron、暂停恢复或编程式即时触发。
_Avoid_: 调度中心客户端, AdminClient, JobManager

**XxlJobTestHelper (脱网调试门面)**:
面向本地开发与自动化单测的脱网调度驱动器，允许在不启动独立 XXL-JOB Admin 的前提下，直接在内存中编程式模拟调度中心触发任意 JobHandler 并强断言执行结果。
_Avoid_: 测试工具, 调试器, JobMockRunner, TestTrigger
