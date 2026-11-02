# ateng-boot-job-snail 基础设施 Starter

> 面向企业级分布式微服务环境的 SnailJob 调度与协同重试双擎生产加固基础设施 Starter（基于官方 1.10.0 稳定基线，JDK 17 LTS 生产标准并前向兼容 2.x），坚持纯原生、零臃肿依赖与显式强类型契约设计。提供方法级 `@AtengSnailJob` 调度生产增强注解、`@AtengSnailRetry` 协同重试增强注解、MapReduce 动态分布式切片规约、`SnailJobInterceptor` 三阶生命周期与金丝雀灰度责任链、`SnailJobRuntimeGovernor` 节点级任务运行时动态治理总线、K8s 停机三阶平滑排空状态机（`GracefulDrainStateMachine`）、本地硬超时强中断看门狗与就地降级补偿（`SnailLocalWatchdog`）、SLF4J 动态日志双写镜像（`SnailJobLogbackAppender`）、四维全链路上下文透传与远程重试保真还原（MDC `traceId` / 操作人 / 租户模式 / 调度上下文）、分布式远程重试上下文存储 SPI（`SnailRetryContextStore`）、大数据游标流式与广播分片防 OOM 模板（`SnailShardingBatchTemplate` 携带 `FailedBatch` 追溯）、官方 OpenAPI 编程式管理门面（`SnailJobAdminTemplate`）、多租户隔离遍历模板（`TenantJobTemplate`）、编程式分布式重试门面（`SnailRetryTemplate`）、告警风暴抑制三阶状态机（首发告警 / 聚合摘要 / 故障自愈）、死信领域事件直通通道（`SnailRetryDeadLetterEvent`）、Spring Boot Actuator 运维管控端点（`/actuator/snailjobs`）、健康探针（`/actuator/health`）、Micrometer 监控度量及脱网极速调试门面（`SnailJobTestHelper`）。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.job.snail.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **基础装配** | 客户端双擎装配 | `AtengSnailJobAutoConfiguration` | `enabled`<br>`server`<br>`group`<br>`namespace`<br>`token`<br>`port`<br>`timeout`<br>`managed-pool-enabled`<br>`max-payload-size` | 遵循 Spring Boot 3 规范与防御性装配铁律，缺配置静默探测，全 Bean 标配缺省守卫 | `AtengSnailJobAutoConfigurationTest` |
| **声明式调度** | 调度增强注解 | `@AtengSnailJob` | `enabled`<br>`job.enabled`<br>`job.context-aspect-enabled`<br>`default-job-timeout`<br>`watchdog-grace-period` | 声明式标注于 `@JobExecutor` 方法上，按需织入四维上下文、本地硬看门狗与租户隔离模式 | `SnailJobContextAspectTest` / `SimpleDemoJobHandler` |
| **声明式调度** | 任务标识名称 | `@AtengSnailJob.value` | - | `String value()`，指定任务标识名称（留空时优先自动取原生 `@JobExecutor.name()`） | `SnailJobContextAspectTest` / `SimpleDemoJobHandler` |
| **声明式调度** | 本地硬超时阈值 | `@AtengSnailJob.timeout` | `default-job-timeout`<br>`watchdog-grace-period` | `String timeout()`，声明式本地硬超时字符串（如 `80ms`、`10s`），看门狗超限强行中断工作线程 | `SnailJobTimeoutFallbackTest` / `TimeoutFallbackDemoJobHandler` |
| **声明式调度** | 就地降级补偿方法 | `@AtengSnailJob.fallbackMethod` | - | `String fallbackMethod()`，超时被看门狗硬中断后就地调用的降级补偿方法名称 | `SnailJobTimeoutFallbackTest` / `TimeoutFallbackDemoJobHandler` |
| **声明式调度** | 租户隔离策略 | `@AtengSnailJob.tenantMode` | `job.tenant-template-enabled` | `JobTenantMode tenantMode()`，支持 `NONE`、`IGNORE`（平台忽略）、`PARAM`（入参绑定）、`ITERATE`（租户遍历） | `SnailJobContextAspectTest` / `TenantJobHandler` |
| **声明式调度** | 告警开关声明 | `@AtengSnailJob.alert` | `alert.enabled`<br>`alert.suppress-duration`<br>`alert.suppress-threshold` | `boolean alert()`，是否在任务执行异常时接入三阶告警防风暴聚合状态机 | `SnailJobAlertAggregatorTest` / `AlertDemoJobHandler` |
| **声明式重试** | 协同重试注解 | `@AtengSnailRetry` | `enabled`<br>`retry.enabled`<br>`retry.context-aspect-enabled` | 声明式标注于 `@Retryable` 方法上，封装四维现场快照封包、跨节点还原与死信通道 | `SnailRetryContextAspectTest` / `RetryDemoService` |
| **声明式重试** | 业务重试场景 | `@AtengSnailRetry.scene` | - | `String scene()`，指定重试业务场景唯一标识（优先以原生 `@Retryable.scene()` 为准） | `SnailRetryContextAspectTest` / `RetryDemoService` |
| **声明式重试** | SpEL 幂等键表达式 | `@AtengSnailRetry.idempotentSpel` | - | `String idempotentSpel()`，支持 SpEL 动态解析业务方法入参生成分布式唯一幂等键 | `IdempotentIdGeneratorTest` / `RetryDemoService` |
| **声明式重试** | 业务单号表达式 | `@AtengSnailRetry.bizNoSpel` | - | `String bizNoSpel()`，提取业务单据编号（如订单号），便于在服务端运维后台检索追踪 | `IdempotentIdGeneratorTest` / `RetryDemoService` |
| **声明式重试** | 死信告警开关 | `@AtengSnailRetry.deadLetterAlert` | `alert.enabled` | `boolean deadLetterAlert()`，重试用尽触发死信时是否广播告警领域事件 | `SnailRetryDeadLetterCallbackTest` / `RetryDemoService` |
| **声明式重试** | 上下文快照封包 | `@AtengSnailRetry.captureContext` | - | `boolean captureContext()`，是否自动将当前 MDC、多租户及操作人封装进重试现场 | `SnailRetryContextAspectTest` / `RetryDemoService` |
| **MapReduce** | Map 切片拆分注解 | `@MapExecutor` | `job.enabled` | `@MapExecutor(taskName = "...")`，将大数据集切片分发为细粒度子任务（支持 `MapHandler.doMap`） | `SnailJobContextAspectTest` / `MapReduceDemoJobHandler` |
| **MapReduce** | Reduce 规约汇总注解 | `@ReduceExecutor` | `job.enabled` | `@ReduceExecutor`，归集各子切片节点的计算结果并执行聚合规约 | `SnailJobContextAspectTest` / `MapReduceDemoJobHandler` |
| **MapReduce** | MergeReduce 合并规约 | `@MergeReduceExecutor` | `job.enabled` | `@MergeReduceExecutor`，多节点规约结果的全局最终合并规约 | `SnailJobContextAspectTest` / `MapReduceDemoJobHandler` |
| **生命周期拦截** | 调度生命周期拦截器 SPI | `SnailJobInterceptor` | `job.context-aspect-enabled` | 提供 `preHandle`（返回 `false` 优雅跳过）、`postHandle` 与 `afterCompletion` 逆序清理扩展点 | `SnailJobInterceptorTest` / `SampleAuditJobInterceptor` |
| **生命周期拦截** | 拦截器上下文模型 | `SnailJobExecutionContext` | - | 封装 `targetBean`、`method`、`args`、`metadata`，支持 `setAttribute` / `getAttribute` 阶段共享 | `SnailJobInterceptorTest` / `SampleAuditJobInterceptor` |
| **超时降级** | 超时降级处理器 SPI | `JobTimeoutFallbackHandler` | `default-job-timeout`<br>`watchdog-grace-period` | `void onTimeout(SnailJobExecutionMetadata metadata, Duration elapsed)` 全局超时降级补偿扩展点 | `SnailJobTimeoutFallbackTest` |
| **运行时治理** | 节点级运行时治理总线 | `SnailJobRuntimeGovernor` | - | `pause(handler)`、`resume(handler)`、`resumeAll()`、`isPaused(handler)`，支持冒号分级继承感知 | `SnailJobRuntimeGovernorAspectTest` / `SampleJobSnailIntegrationTest` |
| **参数防卫校验** | JSR-303 快速失败 | `ValidatorUtil` / `@Valid` | - | 在调度任务中对强类型 DTO 执行无状态语法与业务不变量防卫，失败即 Fail-Fast | `SnailJobContextAspectTest` / `ParamValidationJobHandler` |
| **编程式重试** | 编程式闭包重试 | `SnailRetryTemplate` | `retry.enabled` | `execute(scene, callback)` 自动编排四维快照、退避重试与因果链异常转译 | `SnailRetryTemplateTest` / `RetryDemoService` |
| **编程式重试** | 幂等编程式重试 | `SnailRetryTemplate` | `retry.enabled` | `executeWithIdempotent(scene, idempotentId, bizNo, callback)` 携带显式幂等键与业务单号执行 | `SnailRetryTemplateTest` / `RetryDemoService` |
| **编程式重试** | 对称手动上报 | `SnailRetryTemplate` | `retry.enabled` | `reportManualRetry(scene, targetClassName, params, idempotentId, bizNo)` 外部调用失败主动上报服务端 | `SnailRetryTemplateTest` / `RetryDemoService` |
| **重试上下文** | 远程重试上下文存储 SPI | `SnailRetryContextStore` | `retry.enabled` | `put(key, snapshot)`、`get(key)`、`remove(key)`、`clear()`，默认内存 LRU 实现，支持分布式扩展 | `SnailRetryContextAspectTest` / `InMemorySnailRetryContextStoreTest` |
| **分片流式批处理** | 步长流式顺序消费 | `SnailShardingBatchTemplate` | `job.batch-template-enabled` | `executeStep(initialId, batchSize, queryFunc, idExtractor, consumer)` 游标单线程拉取 | `SnailShardingBatchTemplateTest` |
| **分片流式批处理** | 步长逐条流式消费 | `SnailShardingBatchTemplate` | `job.batch-template-enabled` | `executeStepForEach(...)` 游标逐条拉取消费，彻底告别深分页与堆 OOM | `SnailShardingBatchTemplateTest` |
| **分片流式批处理** | 分片智能过滤流式消费 | `SnailShardingBatchTemplate` | `job.batch-template-enabled` | `executeShardedStep(sharding, initialId, batchSize, queryFunc, idExtractor, consumer)` | `SnailShardingBatchTemplateTest` / `ShardingBatchJobHandler` |
| **分片流式批处理** | 步长受管并发消费 | `SnailShardingBatchTemplate` | `job.batch-template-enabled` | `executeStepParallel(...)` 结合信号量背压并发分片拉取，透传四维上下文 | `SnailShardingBatchTemplateTest` |
| **分片流式批处理** | 分片广播受管并发消费 | `SnailShardingBatchTemplate` | `job.batch-template-enabled` | `executeShardedStepParallel(...)` 广播分片智能切片 + 受管线程池并发消费 | `SnailShardingBatchTemplateTest` / `ShardingBatchJobHandler` |
| **分片流式批处理** | 执行结果统计模型 | `BatchExecutionResult` | - | 封装 `totalCount`、`successCount`、`failCount`、`batchCount`、`duration` 及 `failedBatches` 列表 | `SnailShardingBatchTemplateTest` / `ShardingBatchJobHandler` |
| **分片流式批处理** | 失败分片游标模型 | `FailedBatch<K>` | - | 封装失败批次起始游标 `cursorStart`、结束游标 `cursorEnd`、分片索引、条数与异常因果，支持精准追溯补偿 | `SnailShardingBatchTemplateTest` |
| **ORM 流式扩展** | MyBatis-Plus 游标批处理 | `SnailMyBatisStreamingTemplate` | `job.batch-template-enabled` | `executeStreaming(...)` 联动 `StreamingQueryTemplate` 与 `Cursor` 顺序/并发流式拉取 | `SnailShardingBatchTemplateTest` |
| **OpenAPI 门面** | 任务管理全生命周期 | `SnailJobAdminTemplate` | `openapi.enabled`<br>`openapi.port`<br>`openapi.prefix`<br>`openapi.https`<br>`openapi.timeout`<br>`openapi.max-retries`<br>`openapi.retry-interval` | `getJobDetail`、`saveJob`、`updateJob`、`deleteJobs`、`triggerJob`、`updateJobStatus` | `SnailJobAdminTemplateTest` |
| **OpenAPI 门面** | 工作流 DAG 编排管理 | `SnailJobAdminTemplate` | `openapi.enabled`<br>`openapi.port`<br>`openapi.prefix`<br>`openapi.https`<br>`openapi.timeout`<br>`openapi.max-retries`<br>`openapi.retry-interval` | `getWorkflowDetail`、`saveWorkflow`、`updateWorkflow`、`deleteWorkflows`、`triggerWorkflow` | `SnailJobAdminTemplateTest` |
| **OpenAPI 门面** | 死信重试与治理 | `SnailJobAdminTemplate` | `openapi.enabled`<br>`openapi.port`<br>`openapi.prefix`<br>`openapi.https`<br>`openapi.timeout`<br>`openapi.max-retries`<br>`openapi.retry-interval` | `retryDeadLetter`、`rollbackDeadLetter`、`deleteDeadLetters` 运维重试与状态回滚 | `SnailJobAdminTemplateTest` |
| **多租户遍历** | 多租户隔离遍历执行模板 | `TenantJobTemplate` | `job.tenant-template-enabled` | `executeForEachTenant(action)`，在独立沙箱上下文中流式遍历各租户执行，杜绝数据串写 | `TenantJobTemplateTest` / `TenantJobHandler` |
| **多租户遍历** | 租户列表提供者 SPI | `TenantListProvider` | - | `List<String> getTenantIds()`，供业务系统注册有效租户提供者，配合模板遍历 | `TenantJobTemplateTest` |
| **脱网极速调试** | 脱网单测普通任务触发 | `SnailJobTestHelper` | - | `triggerJob(jobName, param, shardIndex, shardTotal, tenantId)` 脱网本地内省触发 Job | `SnailJobTestHelperTest` / `SampleJobSnailIntegrationTest` |
| **脱网极速调试** | 脱网单测 Map 切片触发 | `SnailJobTestHelper` | - | `triggerMap(jobName, taskName, param)` 触发 `@MapExecutor` 切片任务并组装 `MapArgs` | `SnailJobTestHelperTest` / `SampleJobSnailIntegrationTest` |
| **脱网极速调试** | 脱网单测 Reduce 规约触发 | `SnailJobTestHelper` | - | `triggerReduce(jobName, param)` 触发 `@ReduceExecutor` 规约任务并组装 `ReduceArgs` | `SnailJobTestHelperTest` / `SampleJobSnailIntegrationTest` |
| **脱网极速调试** | 脱网单测 MergeReduce 触发 | `SnailJobTestHelper` | - | `triggerMergeReduce(jobName, param)` 触发 `@MergeReduceExecutor` 最终合并任务 | `SnailJobTestHelperTest` / `SampleJobSnailIntegrationTest` |
| **脱网极速调试** | 脱网单测重试触发 | `SnailJobTestHelper` | - | `triggerRetry(scene, args)`、`triggerRetryWithExecutor(scene, executor, args)` 驱动重试 | `SnailJobTestHelperTest` / `SampleJobSnailIntegrationTest` |
| **运维端点** | 全量任务与重试清单 | `SnailJobEndpoint` | `enabled` | `GET /actuator/snailjobs`，列出已注册的 Job/Retry 概览、在途任务与排空状态 | `SnailJobEndpointTest` / `SampleJobSnailIntegrationTest` |
| **运维端点** | 单任务详情与反射元数据 | `SnailJobEndpoint` | `enabled` | `GET /actuator/snailjobs/{jobName}`，查询指定任务的反射元数据、目标类与方法签名 | `SnailJobEndpointTest` |
| **运维端点** | 运行时治理写入操作 | `SnailJobEndpoint` | `enabled` | `@WriteOperation` `manageGovernor(action, jobName)`，支持 pause / resume / resumeAll | `SnailJobEndpointTest` / `SampleJobSnailIntegrationTest` |
| **运维端点** | 在线手动运维触发 | `SnailJobEndpoint` | `enabled` | `@WriteOperation` `manageJob(jobName, action, args)`，支持在线脱机手动触发一次执行 | `SnailJobEndpointTest` |
| **健康探针** | 执行器双擎健康检测 | `SnailJobHealthIndicator` | `enabled` | 暴露于 `/actuator/health`，动态探测 SnailJob 服务端通信活性、Netty 通道与停机态 | `SnailJobHealthIndicatorTest` / `SampleJobSnailIntegrationTest` |
| **监控度量** | Micrometer 核心指标 | `SnailJobMetricsBinder` | - | 发布 `ateng.job.snail.job.execution` 与 `ateng.job.snail.retry.execution` Timer 指标，及 `inFlight` 与 `paused` 指标 | `SnailJobMetricsBinderTest` |
| **告警自愈** | 告警风暴抑制器 | `SnailJobAlertAggregator` | `alert.enabled`<br>`alert.suppress-duration`<br>`alert.suppress-threshold` | 聚合失败异常，首发立即告警 -> 抑制窗口内静默聚合为摘要 -> 成功时自愈恢复 | `SnailJobAlertAggregatorTest` / `AlertDemoJobHandler` |
| **告警自愈** | 调度告警领域事件 | `SnailJobAlertEvent` | - | Spring 标准领域事件，携带 `alertType` (`INITIAL_FAILURE` / `FAILURE_SUMMARY` / `RECOVERY`) | `SnailJobAlertAggregatorTest` / `SampleJobSnailIntegrationTest` |
| **死信治理** | 重试死信领域事件 | `SnailRetryDeadLetterEvent` | - | Spring 标准领域事件，携带 `scene`、`bizNo`、`idempotentId`、`retries`、`throwable` | `SnailRetryDeadLetterCallbackTest` / `SampleJobSnailIntegrationTest` |
| **死信治理** | 重试完成死信回调 SPI | `SnailRetryDeadLetterCallback` | - | 实现 SnailJob `RetryCompleteCallback`，在重试最大次数终态自动桥接死信告警卡片 | `SnailRetryDeadLetterCallbackTest` |
| **看门狗机制** | 本地硬超时守护时间轮 | `SnailLocalWatchdog` | `default-job-timeout`<br>`watchdog-grace-period` | 基于 Netty `HashedWheelTimer` 与受管线程工厂，超限强行中断工作线程并广播事件 | `SnailLocalWatchdogTest` |
| **停机排空** | 三阶平滑排空状态机 | `GracefulDrainStateMachine` | `drain-timeout` | 阶段一标记排空 -> 阶段二轮询在途排空 -> 阶段三物理断开 Netty 通道，防御数据脏写 | `GracefulDrainStateMachineTest` |
| **上下文传播** | 调度上下文与多租户隔离 | `SnailJobContextAspect` | `job.context-aspect-enabled`<br>`managed-pool-enabled`<br>`max-payload-size` | 治理四维快照 (MDC/User/Tenant/SnailContext) 跨线程透传与确定性清理 | `SnailJobContextAspectTest` / `SimpleDemoJobHandler` |
| **日志镜像** | SLF4J 动态双写镜像 | `SnailJobLogbackAppender` | `log-mirror-enabled`<br>`max-payload-size` | 动态向 Logback Root Logger 注入 Appender，在调度与重试期间将日志镜像至服务端 | `SnailJobLogbackAppenderTest` / `SimpleDemoJobHandler` |
| **自动装配** | 模块自动装配入口 | `AtengSnailJobAutoConfiguration` | `enabled` | Spring Boot 3 标准自动装配，平滑探测服务端配置，标配完整缺省 Bean 守卫 | `AtengSnailJobAutoConfigurationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-job-snail` 是专为分布式生产级微服务、复杂切片计算、DAG 工作流与分布式异常重试集群打造的双擎高韧性基础设施 Starter：
- **纯原生零臃肿底座**：100% 依托 Spring Boot 3 与 JDK 17 原生能力，零引入第三方全量工具包；
- **官方 1.10.0 稳定基线**：锁定 SnailJob 官方生产稳定基线 `1.10.0`，前瞻兼容 2.x API 演进；
- **三阶平滑停机排空状态机**：服务停机时先激活排空标记阻断新任务派发，随后根据 `drain-timeout` 轮询等待活跃调度与重试在途排空，最后销毁通信物理通道句柄，杜绝强杀进程引发的数据脏状态；
- **四维上下文全链路透传与保真还原**：调度与重试执行期间自动生成 32 位无锁 MDC `traceId`，无缝继承租户模式（`IGNORE` / `PARAM` / `ITERATE`）与操作人信息；在分布式远程重试跨时间、跨节点重放时保真还原当时快照，任务终态严格清理现场；
- **MapReduce 动态切片与分布式规约**：基于 `@MapExecutor`、`@ReduceExecutor` 与 `@MergeReduceExecutor`，支持大规模数据多阶段动态拆分计算；
- **调度生命周期与金丝雀灰度拦截责任链**：提供 `SnailJobInterceptor` SPI，支持 `preHandle`（返回 `false` 优雅跳过）、`postHandle` 与 `afterCompletion` 逆序清理，支持金丝雀灰度无感跳过；
- **本地硬看门狗与就地降级补偿**：任务超时被本地 `SnailLocalWatchdog` 强中断后，支持无缝路由至同 Bean 的 `fallbackMethod` 降级补偿方法，并提供全局 `JobTimeoutFallbackHandler` SPI；
- **节点级任务运行时动态治理**：提供 `SnailJobRuntimeGovernor`，支持单节点动态暂停、恢复特定任务及冒号分级感知（`root:slice`），配合 Actuator 端点实现秒级热点止血；
- **大数据流式游标防 OOM 模板**：提供 `SnailShardingBatchTemplate`，基于主键步长流式分批与背压并发消费，附带 `FailedBatch` 精准追溯；具备 MyBatis-Plus 游标扩展类 `SnailMyBatisStreamingTemplate`；
- **多租户隔离遍历模板**：提供 `TenantJobTemplate`，结合 `TenantListProvider` SPI，在独立租户沙箱中流式安全执行；
- **编程式分布式重试门面**：提供 `SnailRetryTemplate`，支持对称手动上报、动态幂等计算与受管重试闭包；
- **告警风暴抑制与故障自愈**：失败首发告警、静默期内自动收敛为富文本聚合摘要卡片、故障修复后自动发送绿色自愈通知，并支持领域事件与协同机器人双通道分发；
- **云原生运维度量与脱网极速调试**：内置 `/actuator/snailjobs` 运维端点、`/actuator/health` 健康探针、Micrometer 百分位监控指标与 `SnailJobTestHelper` 本地脱网即时触发门面。

### Maven 坐标引入

在已导入 `ateng-boot-bom` 的工程中，无需声明版本号即可直接引入：

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-job-snail</artifactId>
</dependency>
```

---

## 2. 最小起步体验 (Happy Path & Out-of-the-Box)

以下代码与配置 100% 逆向提取自已在 CI 与本地全量全绿跑通的场景样例工程 [`ateng-boot-sample-job-snail`](../ateng-boot-samples/ateng-boot-sample-job-snail)。

### 步骤一：配置应用属性 (`application.yml`)

```yaml
spring:
  application:
    name: ateng-boot-sample-job-snail
  config:
    import: "optional:classpath:application-local.yml"

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,snailjobs
  endpoint:
    health:
      show-details: always

ateng:
  boot:
    job:
      snail:
        enabled: true                                      # 是否启用 SnailJob 双擎自动装配
        server: "127.0.0.1:17888"                          # SnailJob 服务端通信地址
        group: "ateng-boot-sample-job-snail"               # 执行器组标识
        namespace: "default"                               # 命名空间
        token: "default_token"                             # 通信访问令牌
        port: 17889                                        # 本地 RPC 通信端口
        timeout: 10s                                       # 通信超时阈值 (Duration 强类型)
        drain-timeout: 30s                                 # K8s 平滑停机最大排空等待时长
        default-job-timeout: 1h                            # 默认看门狗硬超时阈值 (防无界挂起)
        log-mirror-enabled: true                           # 是否启用 SLF4J 动态日志双写镜像
        managed-pool-enabled: true                         # 是否将任务提交至 Core 受管异步线程池
        job:
          enabled: true                                    # 是否启用分布式任务调度功能
          context-aspect-enabled: true                     # 是否启用调度四维上下文切面
          batch-template-enabled: true                     # 是否启用分片游标批处理模板
          tenant-template-enabled: true                    # 是否启用多租户隔离遍历模板
        retry:
          enabled: true                                    # 是否启用分布式协同重试功能
          context-aspect-enabled: true                     # 是否启用重试四维现场还原切面
        openapi:
          enabled: true                                    # 是否启用 OpenAPI 编程式管理门面
          port: 8080
          prefix: "snail-job"
          https: false
        alert:
          enabled: true                                    # 是否启用告警防风暴聚合状态机
          suppress-duration: 30s                           # 告警静默抑制窗口周期
          suppress-threshold: 3                            # 触发告警摘要的连续失败次数阈值
```

### 步骤二：编写声明式任务 Handler

```java
package io.github.atengk.boot.sample.job.snail.handler;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SimpleDemoJobHandler {

    /**
     * 极简调度任务：问候任务 (自动注入 MDC traceId，日志自动双写镜像到 SnailJob 控制台)
     */
    @JobExecutor(name = "simpleGreetingJob")
    public ExecuteResult simpleGreetingJob() {
        String traceId = SnailJobContextHolder.getTraceId();
        log.info("[SimpleDemoJobHandler] 执行极简问候任务, traceId: {}", traceId);
        return ExecuteResult.success("Greeting executed with traceId: " + traceId);
    }

    /**
     * 超时守卫增强调度任务 (超时后由本地 SnailLocalWatchdog 时间轮强行中断)
     */
    @AtengSnailJob(timeout = "500ms")
    @JobExecutor(name = "timeoutGuardedJob")
    public ExecuteResult timeoutGuardedJob() {
        log.info("[SimpleDemoJobHandler] 执行超时守卫增强任务, 当前租户: {}", SnailJobContextHolder.getTenantId());
        return ExecuteResult.success("Timeout guarded job succeeded");
    }
}
```

### 步骤三：编写声明式重试服务

```java
package io.github.atengk.boot.sample.job.snail.service;

import com.aizuda.snailjob.client.core.annotation.Retryable;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.job.snail.annotation.AtengSnailRetry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetryDemoService {

    private final AtomicInteger attemptCount = new AtomicInteger(0);

    /**
     * 声明式重试方法：订单支付 (通过 SpEL 动态生成幂等键与业务单号，绑定四维现场快照)
     */
    @AtengSnailRetry(scene = "orderPayRetry")
    @Retryable(scene = "orderPayRetry")
    public String payOrder(String orderId, Double amount) {
        int currentAttempt = attemptCount.incrementAndGet();
        String currentTenant = TenantContextHolder.getTenantId();
        log.info("[RetryDemoService] 正在处理支付请求: orderId={}, amount={}, 租户={}, 尝试次数={}",
                orderId, amount, currentTenant, currentAttempt);

        if ("FAIL_ORDER".equals(orderId)) {
            throw new IllegalStateException("模拟支付网关网络超时 [orderId: " + orderId + "]");
        }

        return "PAY_SUCCESS:" + orderId + ":ATTEMPT_" + currentAttempt;
    }
}
```

---

## 3. 生产扩展接管 (Customization & Extensibility)

### 3.1 MapReduce 动态切片与规约聚合 (`MapReduceDemoJobHandler`)

在大数据批处理计算中，框架原生支持 MapReduce 三阶段任务拆分与规约：

```java
package io.github.atengk.boot.sample.job.snail.handler;

import com.aizuda.snailjob.client.job.core.MapHandler;
import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.annotation.MapExecutor;
import com.aizuda.snailjob.client.job.core.annotation.MergeReduceExecutor;
import com.aizuda.snailjob.client.job.core.annotation.ReduceExecutor;
import com.aizuda.snailjob.client.job.core.dto.MapArgs;
import com.aizuda.snailjob.client.job.core.dto.MergeReduceArgs;
import com.aizuda.snailjob.client.job.core.dto.ReduceArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@JobExecutor(name = "mapReduceDemoJob")
public class MapReduceDemoJobHandler {

    /**
     * 1. Map 切片拆分任务：将大批量数据拆分子切片并通过 MapHandler 分发
     */
    @MapExecutor(taskName = "splitTask")
    public ExecuteResult splitTask(MapArgs args, MapHandler<String> handler) {
        String taskName = args != null ? args.getTaskName() : "splitTask";
        log.info("[MapReduceDemoJobHandler] 执行 Map 拆分切片任务, taskName: {}", taskName);

        List<String> subTasks = List.of("TASK-CHUNK-1", "TASK-CHUNK-2", "TASK-CHUNK-3");
        if (handler != null) {
            ExecuteResult mapRes = handler.doMap(subTasks, "processTask");
            log.info("[MapReduceDemoJobHandler] doMap 分发子任务完成: {}", mapRes);
        }
        return ExecuteResult.success("Map slice splitTask executed with " + subTasks.size() + " subtasks");
    }

    /**
     * 2. Reduce 规约汇总任务：汇总各计算节点的局部规约结果
     */
    @ReduceExecutor
    public ExecuteResult reduceTask(ReduceArgs args) {
        log.info("[MapReduceDemoJobHandler] 执行 Reduce 规约汇总任务, param: {}", args.getJobParams());
        return ExecuteResult.success("Reduce summary completed for params: " + args.getJobParams());
    }

    /**
     * 3. MergeReduce 全局最终合并规约任务
     */
    @MergeReduceExecutor
    public ExecuteResult mergeReduceTask(MergeReduceArgs args) {
        log.info("[MapReduceDemoJobHandler] 执行 MergeReduce 最终合并任务, param: {}", args.getJobParams());
        return ExecuteResult.success("MergeReduce final aggregation completed for params: " + args.getJobParams());
    }
}
```

### 3.2 调度生命周期拦截器与金丝雀灰度绕过 (`SnailJobInterceptor`)

实现 `SnailJobInterceptor` 接口可拦截全量调度与切片任务，通过 Spring `@Order` 编排责任链时序。`preHandle` 返回 `false` 将优雅跳过下游任务并向调度中心反馈成功，防止金丝雀节点误执行产生脏数据：

```java
package io.github.atengk.boot.sample.job.snail.interceptor;

import io.github.atengk.boot.job.snail.interceptor.SnailJobExecutionContext;
import io.github.atengk.boot.job.snail.interceptor.SnailJobInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(10)
public class SampleAuditJobInterceptor implements SnailJobInterceptor {

    @Override
    public boolean preHandle(SnailJobExecutionContext context) {
        String jobName = context.getMetadata() != null ? context.getMetadata().getJobName() : "";

        // 金丝雀灰度条件判定：若命中绕过规则，返回 false 优雅跳过任务
        if ("canaryBypassJob".equals(jobName)) {
            log.info("[SampleAuditJobInterceptor] 命中金丝雀灰度绕过策略，优雅跳过调度: jobName={}", jobName);
            return false;
        }

        // 挂载阶段共享属性并放行
        context.setAttribute("auditStartTimeNanos", System.nanoTime());
        return true;
    }

    @Override
    public void postHandle(SnailJobExecutionContext context, Object result) {
        log.info("[SampleAuditJobInterceptor] 调度后置审计完成: result={}", result);
    }

    @Override
    public void afterCompletion(SnailJobExecutionContext context, Object result, Throwable ex) {
        Long startNanos = context.getAttribute("auditStartTimeNanos", Long.class);
        long elapsedNanos = startNanos != null ? (System.nanoTime() - startNanos) : 0L;
        log.info("[SampleAuditJobInterceptor] 调度终态审计闭环: elapsedNs={}, error={}",
                elapsedNanos, ex != null ? ex.getMessage() : "none");
    }
}
```

### 3.3 本地硬超时中断与就地降级补偿 (`@AtengSnailJob.fallbackMethod`)

当任务被 `SnailLocalWatchdog` 超时中断后，框架自动优先调用当前 Bean 声明的 `fallbackMethod` 就地降级补偿，恢复业务现场并向服务端反馈成功：

```java
package io.github.atengk.boot.sample.job.snail.handler;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TimeoutFallbackDemoJobHandler {

    @JobExecutor(name = "timeoutFallbackJob")
    @AtengSnailJob(timeout = "80ms", fallbackMethod = "executeFallbackCompensation")
    public ExecuteResult executeWithTimeout(JobArgs args) throws InterruptedException {
        log.info("[TimeoutFallbackDemoJobHandler] 开始执行耗时任务 (模拟执行 500ms)...");
        Thread.sleep(500); // 超过 80ms 阈值，看门狗强行发出 interrupt()
        return ExecuteResult.success("Normal execution completed unexpectedly");
    }

    /**
     * 就地降级补偿方法：形参自动匹配原 JobArgs / 上下文
     */
    public ExecuteResult executeFallbackCompensation(JobArgs args) {
        String params = args != null && args.getJobParams() != null ? String.valueOf(args.getJobParams()) : "default";
        log.warn("[TimeoutFallbackDemoJobHandler] 触发超时就地降级补偿逻辑, params: {}", params);
        return ExecuteResult.success("Fallback compensation executed successfully: " + params);
    }
}
```

### 3.4 节点级任务运行时动态治理 (`SnailJobRuntimeGovernor`)

无需重启服务节点即可动态暂停、恢复指定任务。支持**冒号分级感知**（暂停 `mapReduceDemoJob` 时，切片任务 `mapReduceDemoJob:splitTask` 自动同步感知暂停并安全跳过）：

```java
@Autowired
private SnailJobRuntimeGovernor governor;

// 1. 暂停指定普通任务
governor.pause("simpleGreetingJob");
boolean paused = governor.isPaused("simpleGreetingJob"); // true

// 2. 暂停根任务，切片子任务自动分级感知
governor.pause("mapReduceDemoJob");
boolean slicePaused = governor.isPaused("mapReduceDemoJob:splitTask"); // true (自动继承根任务暂停态)

// 3. 恢复指定任务
governor.resume("simpleGreetingJob");

// 4. 全量一键恢复
int resumedCount = governor.resumeAll();
```

### 3.5 JSR-303 强类型参数校验与 Fail-Fast

结合 Core 模块的 `ValidatorUtil` 编程式防御设施，在调度任务执行器中接收参数并执行语法与不变量校验；校验不通过直接 Fail-Fast 阻断，记录结构化错误日志并触发首发告警：

```java
package io.github.atengk.boot.sample.job.snail.handler;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.ValidatorUtil;
import io.github.atengk.boot.sample.job.snail.dto.UserSyncParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class ParamValidationJobHandler {

    /**
     * 用户同步任务：强校验批次与分页大小入参
     */
    @JobExecutor(name = "userSyncJob")
    public ExecuteResult userSyncJob(JobArgs args) {
        Object rawParams = args != null ? args.getJobParams() : null;
        log.info("[ParamValidationJobHandler] 开始执行用户同步调度任务, 原始参数: {}", rawParams);

        UserSyncParam param;
        if (rawParams instanceof UserSyncParam userSyncParam) {
            param = userSyncParam;
        } else if (rawParams instanceof String json && StringUtils.hasText(json)) {
            param = JsonUtil.parseObject(json, UserSyncParam.class);
        } else {
            return ExecuteResult.failure("任务参数缺失或格式不合法");
        }

        // 第二道防线：基于 ValidatorUtil 编程式强校验业务不变量
        ValidatorUtil.validate(param);

        log.info("[ParamValidationJobHandler] 参数校验通过: batchNo={}, batchSize={}, dept={}",
                param.getBatchNo(), param.getBatchSize(), param.getDepartmentId());

        return ExecuteResult.success("Sync completed for batch " + param.getBatchNo());
    }
}
```

### 3.6 大数据分片广播与流式防 OOM 模板 (`SnailShardingBatchTemplate` + `FailedBatch`)

针对千万级大表与分布式广播分片场景，框架提供 `SnailShardingBatchTemplate`，在方法形参注入 `ShardingContext`，基于主键步长流式切片消费，并自动封装 `FailedBatch` 追溯失败游标：

```java
package io.github.atengk.boot.sample.job.snail.handler;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.job.snail.model.ShardingContext;
import io.github.atengk.boot.job.snail.template.BatchExecutionResult;
import io.github.atengk.boot.job.snail.template.FailedBatch;
import io.github.atengk.boot.job.snail.template.SnailShardingBatchTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShardingBatchJobHandler {

    private final SnailShardingBatchTemplate shardingBatchTemplate;

    @JobExecutor(name = "shardingBatchJob")
    public ExecuteResult shardingBatchJob(JobArgs args) {
        ShardingContext sharding = ShardingContext.current();
        log.info("[ShardingBatchJobHandler] 开始执行分布式分片批处理, 当前分片: {}", sharding);

        // 模拟生成内存测试订单 ID 数据源 (1 ~ 30)
        AtomicInteger processedItems = new AtomicInteger(0);

        BatchExecutionResult<Long> result = shardingBatchTemplate.executeShardedStep(
                sharding,
                0L,
                10,
                (lastId, limit) -> {
                    List<Long> mockBatch = new ArrayList<>();
                    long start = lastId == null ? 1L : lastId + 1L;
                    for (long id = start; id < start + limit && id <= 30; id++) {
                        mockBatch.add(id);
                    }
                    return mockBatch;
                },
                id -> id,
                matchedBatch -> {
                    log.info("[ShardingBatchJobHandler] 分片 [{}] 匹配消费切片批次: 数量={}",
                            sharding != null ? sharding.getShardIndex() : "ALL", matchedBatch.size());
                    processedItems.addAndGet(matchedBatch.size());
                }
        );

        // 检查是否有失败切片并记录游标
        if (!result.failedBatches().isEmpty()) {
            for (FailedBatch<Long> failed : result.failedBatches()) {
                log.error("❌ 分批消费失败游标区间: [{} - {}], 条数: {}, 错误: {}",
                        failed.cursorStart(), failed.cursorEnd(), failed.itemCount(),
                        failed.throwable() != null ? failed.throwable().getMessage() : "unknown");
            }
        }

        log.info("[ShardingBatchJobHandler] 分布式分片批处理完成: 总扫描={}, 实际消费={}, 耗时={}ms",
                result.totalCount(), processedItems.get(), result.duration().toMillis());

        return ExecuteResult.success("Sharding batch completed, scanned=" + result.totalCount() + ", processed=" + processedItems.get());
    }
}
```

### 3.7 编程式分布式重试与对称手动上报 (`SnailRetryTemplate`)

针对无法通过 `@Retryable` 注解声明的动态链路，直接注入 `SnailRetryTemplate` 编程式门面：

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class RetryDemoService {

    private final SnailRetryTemplate retryTemplate;

    /**
     * 编程式闭包重试
     */
    public String executeWithProgrammaticRetry(String orderId) {
        return retryTemplate.executeWithIdempotent("orderPayRetry", orderId, "BIZ-" + orderId, () -> {
            log.info("[RetryDemoService] 编程式重试闭包执行中, orderId={}", orderId);
            return "SUCCESS:" + orderId;
        });
    }

    /**
     * 手动失败上报方法：在脱机或异步失败时向 SnailJob 服务端纳管
     */
    public boolean reportFailedOrder(String orderId) {
        log.info("[RetryDemoService] 手动上报失败重试任务: orderId={}", orderId);
        return retryTemplate.reportManualRetry("orderPayRetry", getClass().getName(),
                new Object[]{orderId, 100.0}, orderId, "BIZ-" + orderId);
    }
}
```

### 3.8 多租户隔离调度任务三模实测 (`TenantJobTemplate`)

```java
package io.github.atengk.boot.sample.job.snail.handler;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import io.github.atengk.boot.job.snail.enums.JobTenantMode;
import io.github.atengk.boot.job.snail.template.BatchExecutionResult;
import io.github.atengk.boot.job.snail.template.TenantJobTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TenantJobHandler {

    private final TenantJobTemplate tenantJobTemplate;

    // 模式一：全局忽略多租户 (平台级归档)
    @AtengSnailJob(tenantMode = JobTenantMode.IGNORE)
    @JobExecutor(name = "globalArchiveJob")
    public ExecuteResult globalArchiveJob() {
        boolean ignoreTenant = TenantContextHolder.isIgnoreTenant();
        log.info("[TenantJobHandler] 执行全局归档任务, 忽略租户标记={}", ignoreTenant);
        return ExecuteResult.success("Global archive finished, ignoreTenant=" + ignoreTenant);
    }

    // 模式二：单租户限定任务 (从入参绑定当前租户)
    @AtengSnailJob(tenantMode = JobTenantMode.PARAM)
    @JobExecutor(name = "tenantScopedJob")
    public ExecuteResult tenantScopedJob(JobArgs args) {
        String currentTenant = TenantContextHolder.getTenantId();
        log.info("[TenantJobHandler] 执行租户专属任务, 租户ID: {}", currentTenant);
        return ExecuteResult.success("Tenant job finished for tenant=" + currentTenant);
    }

    // 模式三：租户批量遍历执行 (按租户沙箱隔离逐一遍历执行)
    @AtengSnailJob(tenantMode = JobTenantMode.ITERATE)
    @JobExecutor(name = "tenantBatchJob")
    public ExecuteResult tenantBatchJob() {
        List<String> tenants = List.of("tenant-alpha", "tenant-beta");
        log.info("[TenantJobHandler] 开始批量遍历执行租户任务: {}", tenants);

        BatchExecutionResult result = tenantJobTemplate.executeForEachTenant(tenants, false, tenantId -> {
            log.info("[TenantJobHandler] 正在沙箱执行租户 [{}] 的结算逻辑, 当前上下文租户={}",
                    tenantId, TenantContextHolder.getTenantId());
        });

        return ExecuteResult.success("Tenant batch finished: total=" + result.totalCount() + ", success=" + result.successCount());
    }
}
```

### 3.9 监听告警与死信领域事件

```java
@Component
public class CustomAlertEventListener {

    private static final Logger log = LoggerFactory.getLogger(CustomAlertEventListener.class);

    @EventListener
    public void onJobAlert(SnailJobAlertEvent event) {
        switch (event.getAlertType()) {
            case INITIAL_FAILURE -> log.error("🚨 任务 [{}] 发生首发故障! traceId={}, error={}",
                    event.getJobName(), event.getTraceId(),
                    event.getThrowable() != null ? event.getThrowable().getMessage() : "unknown");
            case FAILURE_SUMMARY -> log.warn("📊 任务 [{}] 处于告警静默期, 累计失败 {} 次, 故障总历时 {} 秒",
                    event.getJobName(), event.getFailureCount(), event.getFailureDuration().toSeconds());
            case RECOVERY -> log.info("💚 任务 [{}] 故障自愈恢复! 期间曾累计失败 {} 次",
                    event.getJobName(), event.getFailureCount());
        }
    }

    @EventListener
    public void onRetryDeadLetter(SnailRetryDeadLetterEvent event) {
        log.error("💀 重试任务到达最大次数触发死信: scene={}, bizNo={}, idempotentId={}, error={}",
                event.getScene(), event.getBizNo(), event.getIdempotentId(),
                event.getThrowable() != null ? event.getThrowable().getMessage() : "unknown");
    }
}
```

### 3.10 脱网极速调试门面 (`SnailJobTestHelper`)

在无外部 SnailJob 服务端的离线单测或本地 CI 环境下，开发者可直接使用 `SnailJobTestHelper` 触发任意任务，框架自动模拟调度上下文与链路追踪，秒级自洽测试：

```java
@SpringBootTest
class SnailJobOfflineTest {

    @Autowired
    private SnailJobTestHelper testHelper;

    @Test
    void testOfflineExecution() {
        // 1. 无参手动触发
        SnailJobTestResult result = testHelper.triggerJob("simpleGreetingJob", null);
        assertThat(result.isSuccess()).isTrue();

        // 2. 带参手动触发（自动解包底层异常，返回真实业务异常）
        SnailJobTestResult paramResult = testHelper.triggerJob("userSyncJob", "{\"batchNo\":\"BATCH-001\",\"batchSize\":500}");
        assertThat(paramResult.isSuccess()).isTrue();

        // 3. Map 切片触发
        SnailJobTestResult mapResult = SnailJobTestHelper.triggerMap("mapReduceDemoJob", "splitTask", "{\"range\":\"1-100\"}");
        assertThat(mapResult.isSuccess()).isTrue();

        // 4. Reduce 规约触发
        SnailJobTestResult reduceResult = SnailJobTestHelper.triggerReduce("mapReduceDemoJob", "{\"summaryId\":\"SUM-01\"}");
        assertThat(reduceResult.isSuccess()).isTrue();

        // 5. 分片广播模拟触发 (当前分片 0，总分片 2)
        SnailJobTestResult shardResult = testHelper.triggerJob("shardingBatchJob", null, 0, 2);
        assertThat(shardResult.isSuccess()).isTrue();

        // 6. 重试场景模拟触发
        SnailRetryTestResult retryResult = testHelper.triggerRetry("orderPayRetry", "ORD-1001", 88.0);
        assertThat(retryResult.isSuccess()).isTrue();
    }
}
```

---

## 4. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 4.1 三阶平滑停机排空状态机 (`GracefulDrainStateMachine`)
- **阶段一（标记排空）**：服务准备停机时，状态机将 `draining` 置为 `true`，向调度中心反馈拒收信号阻断新任务派发；
- **阶段二（在途排空）**：执行器在配置的 `drain-timeout`（默认 30s）内进行平滑轮询等待活跃调度任务（`inFlightJobs`）与在途重试任务（`inFlightRetries`）执行完成；
- **阶段三（物理销毁）**：优雅销毁底层 Netty 物理通道连接，释放长连接句柄与端口，杜绝强杀进程引发的数据脏状态。

### 4.2 本地硬超时中断看门狗与二阶段降级补偿 (`SnailLocalWatchdog`)
- 官方调度超时判定依赖服务端轮询，容易因网络抖动造成客户端任务无限挂起耗尽线程；
- 本 Starter 引入本地 `SnailLocalWatchdog`，基于 Netty `HashedWheelTimer` 与 Spring 受管线程工厂维护活跃任务时间轮；一旦单次任务执行超过 `@AtengSnailJob.timeout`（或全局 `default-job-timeout`），守护线程立即对目标任务线程调用 `interrupt()` 触发强中断；
- 紧接着切面优先调用当前 Bean 声明的 `fallbackMethod` 就地降级补偿；若当前类未声明或就地降级抛错，自动回退调用全局 `JobTimeoutFallbackHandler` SPI，杜绝任务重试雪崩。

### 4.3 远程重试保真还原与四维快照封包
- 分布式重试跨时间、跨节点重新触发时，原生重试框架会丢失当时的会话上下文；
- 切面在本地执行失败时自动封包当前四维上下文（MDC `traceId`、多租户、操作人与 SnailJob 调度上下文）至重试参数快照中；在远程重放时保真还原当时的环境，并在执行完成时严格清理现场，防止线程复用污染。

### 4.4 告警风暴抑制三阶状态机与状态自愈
- **首发告警 (`INITIAL_FAILURE`)**：当任务首次执行失败时，立即投递首发告警，确保值班人员第一时间获知故障；
- **静默期聚合 (`FAILURE_SUMMARY`)**：在 `alert.suppress-duration`（默认 30s/5m）与 `alert.suppress-threshold`（默认 3 次）双重窗口内抑制单次重复骚扰；在窗口期满或步长溢出时，触发包含累计失败总数与最新异常的聚合卡片，随后重置阶段步长重新进入静默；
- **故障自愈恢复 (`RECOVERY`)**：当任务在经历故障后首次执行成功时，自动发出绿色自愈卡片，汇报期间累计失败总数与历时，清除故障计数，闭环自愈状态机。

### 4.5 Actuator 监控端点与 Micrometer 度量
- **Actuator 运维端点 (`/actuator/snailjobs`)**：
  - `GET /actuator/snailjobs`：获取所有受管任务处理器与重试场景列表、在途任务数与排空状态；
  - `GET /actuator/snailjobs/{name}`：查询指定任务的反射元数据、目标 Bean 与方法签名；
  - `POST /actuator/snailjobs`：通过 `@WriteOperation` 执行节点级运行时治理（`action: pause/resume/resumeAll`）；
  - `POST /actuator/snailjobs/{jobName}`：在线脱网运维手动触发一次执行；
- **健康探针 (`/actuator/health`)**：
  - 探针 `SnailJobHealthIndicator` 动态探测执行器与 SnailJob 服务端的 Netty 长连接通信活性与网络可达性；
- **Micrometer 指标**：
  - 发布 `ateng.job.snail.job.execution` 与 `ateng.job.snail.retry.execution` Timer 度量，携带 `jobName`/`scene`、`status`（success / failure / skipped）、`tenant` 标签，全真上报吞吐量与耗时分布。

---

## 5. 维度四：物理排版与代码全真验证 (Evergreen & Zero-Glitch)

完整、自洽、开箱即用的端到端演示代码 100% 收录于 [`ateng-boot-sample-job-snail`](../ateng-boot-samples/ateng-boot-sample-job-snail)。

### 5.1 14 项全场景端到端集成测试矩阵

基于集成测试套件 [`SampleJobSnailIntegrationTest.java`](../ateng-boot-samples/ateng-boot-sample-job-snail/src/test/java/io/github/atengk/boot/sample/job/snail/SampleJobSnailIntegrationTest.java)，在单测试套件双模自适应架构下，100% 跑通以下 14 大核心场景：

| 序号 | 测试方法名称 | 场景说明 | 核心验证与断言 |
| :---: | :--- | :--- | :--- |
| 1 | `testSimpleGreetingJob` | 极简调度问候任务 | 验证执行器成功执行，MDC `traceId` 自动注入 |
| 2 | `testTimeoutGuardedJob` | 超时守卫增强任务 | 验证 `@AtengSnailJob(timeout = "500ms")` 正常调度通过 |
| 3 | `testMapReduceJob` | MapReduce 分布式切片与规约 | 跑通 Map 切片拆分、Reduce 规约及 MergeReduce 聚合完整链路 |
| 4 | `testCanaryBypassInterceptor` | 金丝雀灰度责任链拦截 | `preHandle` 拦截返回 `false`，优雅跳过下游执行并反馈成功 |
| 5 | `testTimeoutFallbackCompensation` | 本地看门狗超时强中断降级 | 任务耗时超限被时间轮强中断，自动无缝路由至 `fallbackMethod` 补偿 |
| 6 | `testGovernorPauseAndResume` | 运行时治理动态暂停与恢复 | 验证冒号分级动态继承暂停态与一键全局恢复 |
| 7 | `testParamValidationJob` | JSR-303 强类型参数防卫 | 校验参数缺失与非法格式 Fail-Fast，合法入参顺利通过 |
| 8 | `testShardingBatchExecution` | 大数据分片广播流式批处理 | 验证主键步长流式消费与分片过滤，精准记录 `FailedBatch` |
| 9 | `testProgrammaticRetryAndReport` | 编程式重试闭包与对称上报 | 验证 `SnailRetryTemplate` 幂等闭包重试与手动上报 |
| 10 | `testTenantJobModes` | 多租户隔离调度三模实测 | 验证 `IGNORE` 平台级归档、`PARAM` 单租户绑定及 `ITERATE` 沙箱遍历 |
| 11 | `testAlertAndDeadLetterEvents` | 告警风暴抑制与死信领域事件 | 验证首发告警、窗口聚合、自愈通知及重试死信领域事件分发 |
| 12 | `testActuatorEndpointAndMetrics` | Actuator 运维端点与监控度量 | 验证 `/actuator/snailjobs`、`/actuator/health` 与 Micrometer 计数 |
| 13 | `testScenarioServiceFullWorkflow` | 场景服务编排端到端贯通 | 场景服务综合调用 MapReduce、降级补偿、多租户与金丝雀策略 |
| 14 | `testActuatorGovernorOperations` | Actuator 端点运行时写入操作 | 通过端点 `@WriteOperation` 验证在线暂停、恢复与触发 |

---

### 5.2 真实控制台测试输出记录

以下控制台输出直接逆向截取自本地真实执行命令 `mvn test -pl ateng-boot-job-snail,ateng-boot-samples/ateng-boot-sample-job-snail -am -T 1C -nsu -B` 的真实构建输出：

```text
[INFO] Reactor Summary for io.github.atengk:ateng-boot 1.2.0:
[INFO] 
[INFO] io.github.atengk:ateng-boot ........................ SUCCESS [  0.253 s]
[INFO] io.github.atengk:ateng-boot-core ................... SUCCESS [ 13.488 s]
[INFO] io.github.atengk:ateng-boot-mybatis-plus ........... SUCCESS [ 23.856 s]
[INFO] io.github.atengk:ateng-boot-notification ........... SUCCESS [ 16.434 s]
[INFO] io.github.atengk:ateng-boot-job-snail .............. SUCCESS [ 13.643 s]
[INFO] io.github.atengk:ateng-boot-samples ................ SUCCESS [  0.003 s]
[INFO] io.github.atengk:ateng-boot-sample-job-snail ....... SUCCESS [ 18.993 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  01:12 min (Wall Clock)
[INFO] Finished at: 2026-11-02T13:03:41+08:00
[INFO] ------------------------------------------------------------------------
```

### 5.3 离线自洽与定向验收命令

```bash
# 精准定向验收 ateng-boot-job-snail 核心底座与场景样例工程
mvn test -pl ateng-boot-job-snail,ateng-boot-samples/ateng-boot-sample-job-snail -am -T 1C -nsu -B
```
