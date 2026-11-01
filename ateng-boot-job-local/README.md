# ateng-boot-job-local 基础设施 Starter

> 面向单机定时调度与轻量分布式集群防重的生产级调度基础设施 Starter，坚持纯原生零重型臃肿依赖设计。基于 Spring `ThreadPoolTaskScheduler` 时钟调度器与受管 Worker 线程池双层物理隔离体系，提供声明式 `@LocalJob` 注解与对称操作门面 `LocalJobTemplate`、四阶重叠并发防卫策略（`OverlappingStrategy`）、硬超时强中断熔断与指数退避重试、强类型 DTO 入参绑定与 JSR-303 Fail-Fast 防卫、SaaS 多租户全模态隔离与 Fail-Safe 容错、三阶防风暴告警聚合器与故障自愈通知、原生零臃肿 Redis 轻量集群互斥锁与原子防误删、32 位无锁 MDC `traceId` 全链路追踪、有界环形淘汰防堆 OOM、云原生 Micrometer 监控指标度量、`SmartLifecycle` 确定性停机排空以及 `/actuator/localjobs` 生产级运维管控端点。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.job.local.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **声明式调度** | 声明式任务注解 | `@LocalJob` | `enabled` | `@LocalJob(...)` 标注于受管 Bean 方法，自动扫描并注册纳管 | `LocalJobSchedulingTest`<br>`DailyReconciliationScenarioJob` |
| **声明式调度** | 任务标识名称 | `@LocalJob.value` / `name` | - | `String name()`，任务唯一标识，缺省为空时自动推导为 `类名#方法名` | `LocalJobSchedulingTest`<br>`DailyReconciliationScenarioJob` |
| **声明式调度** | 标准 Cron 表达式 | `@LocalJob.cron` | - | `String cron()`，支持标准 Spring 6 位 Cron 表达式（如 `0 0 2 * * ?`） | `LocalJobSchedulingTest`<br>`DailyReconciliationScenarioJob` |
| **声明式调度** | 固定频率调度 | `@LocalJob.fixedRate` | - | `String fixedRate()`，强类型时间（如 `5s`），上次开始到本次开始的间隔 | `LocalJobSchedulingTest`<br>`SystemMetricsSamplingScenarioJob` |
| **声明式调度** | 固定延迟调度 | `@LocalJob.fixedDelay` | - | `String fixedDelay()`，强类型时间（如 `10s`），上次结束到本次开始的间隔 | `LocalJobSchedulingTest`<br>`OrderTimeoutCancelScenarioJob` |
| **声明式调度** | 初始启动延迟 | `@LocalJob.initialDelay` | - | `String initialDelay()`，强类型时间（如 `1h`），容器启动后的首次调度延迟 | `LocalJobSchedulingTest`<br>`OrderTimeoutCancelScenarioJob` |
| **声明式调度** | 并发重叠防卫策略 | `@LocalJob.strategy` | - | `OverlappingStrategy strategy()`，默认 `DISCARD`，支持 `QUEUE` / `CONCURRENT` / `INTERRUPT` | `LocalJobOverlappingAndFacadeTest`<br>`SampleJobLocalIntegrationTest` |
| **声明式调度** | 单次执行硬超时阈值 | `@LocalJob.timeout` | - | `String timeout()`，单次执行超时强行中断阈值（如 `300ms`），缺省不限时 | `LocalJobTimeoutRetryAndMdcTest`<br>`OrderTimeoutCancelScenarioJob` |
| **声明式调度** | 失败指数重试次数 | `@LocalJob.maxRetries` | - | `int maxRetries()`，任务失败后最大自适应重试次数，默认 0 不重试 | `LocalJobTimeoutRetryAndMdcTest`<br>`OrderTimeoutCancelScenarioJob` |
| **声明式调度** | 失败重试退避间隔 | `@LocalJob.retryInterval` | - | `String retryInterval()`，重试退避初始间隔（默认 `1s`），联动 `RetryUtil` 指数退避 | `LocalJobTimeoutRetryAndMdcTest`<br>`OrderTimeoutCancelScenarioJob` |
| **声明式调度** | 轻量集群互斥防重 | `@LocalJob.clusterLock` | `cluster-lock.enabled` | `boolean clusterLock()`，开启多节点集群互斥锁，抢锁失败安全跳过，默认 false | `LocalJobClusterLockTest`<br>`ClusterDeduplicationScenarioJob` |
| **声明式调度** | 集群互斥锁租约时长 | `@LocalJob.clusterLockLeaseTime` | `cluster-lock.default-lease-time` | `String clusterLockLeaseTime()`，防死锁安全租约时长（默认 `5m`） | `LocalJobClusterLockTest`<br>`ClusterDeduplicationScenarioJob` |
| **声明式调度** | SaaS 多租户调度模态 | `@LocalJob.tenantMode` | - | `JobTenantMode tenantMode()`，默认 `IGNORE`，支持 `PARAM` 单租户与 `ITERATE` 全租户轮询 | `LocalJobMultiTenantTest`<br>`MultiTenantBillingScenarioJob` |
| **声明式调度** | 任务人读描述 | `@LocalJob.description` | - | `String description()`，任务中文业务说明，用于 Actuator 与看板展示 | `LocalJobActuatorAndEventTest`<br>`DailyReconciliationScenarioJob` |
| **声明式调度** | 任务静态启用开关 | `@LocalJob.enabled` | - | `boolean enabled()`，单任务静态启动开关，默认 true | `LocalJobSchedulingTest` |
| **操作门面** | 手动立即触发任务 | `LocalJobTemplate` | - | `JobResult trigger(String jobName)`，手动立即触发执行一次指定任务 | `LocalJobFacadeAndQueryTest`<br>`SampleJobLocalIntegrationTest` |
| **操作门面** | 手动带参立即触发 | `LocalJobTemplate` | - | `JobResult trigger(String jobName, Map<String, Object> params)`，携带参数触发 | `LocalJobParamValidationTest`<br>`TypedParamArchivingScenarioJob` |
| **操作门面** | 暂停周期性调度 | `LocalJobTemplate` | - | `boolean pause(String jobName)`，暂停周期自动触发，状态置为 `PAUSED` | `LocalJobFacadeAndQueryTest`<br>`SampleJobLocalIntegrationTest` |
| **操作门面** | 恢复周期性调度 | `LocalJobTemplate` | - | `boolean resume(String jobName)`，恢复暂停的任务，重新挂载至时钟调度池 | `LocalJobFacadeAndQueryTest`<br>`SampleJobLocalIntegrationTest` |
| **操作门面** | 动态修改 Cron 周期 | `LocalJobTemplate` | - | `boolean updateCron(String jobName, String cronExpression)`，动态重置周期并重调度 | `LocalJobFacadeAndQueryTest`<br>`SampleJobLocalIntegrationTest` |
| **操作门面** | 动态修改固定频率周期 | `LocalJobTemplate` | - | `boolean updateFixedRate(String jobName, Duration newFixedRate)`，修改频率并重调度 | `LocalJobFacadeAndQueryTest` |
| **操作门面** | 动态修改固定延迟周期 | `LocalJobTemplate` | - | `boolean updateFixedDelay(String jobName, Duration newFixedDelay)`，修改延迟并重调度 | `LocalJobFacadeAndQueryTest` |
| **操作门面** | 编程式注册通用任务 | `LocalJobTemplate` | - | `boolean register(JobDefinition definition, JobHandler handler)`，完整元数据注册 | `LocalJobFacadeAndQueryTest` |
| **操作门面** | 编程式极简注册 Cron | `LocalJobTemplate` | - | `boolean registerCron(String jobName, String cron, Runnable runnable)`，快捷注册 | `LocalJobFacadeAndQueryTest`<br>`SampleJobLocalIntegrationTest` |
| **操作门面** | 编程式注册固定频率 | `LocalJobTemplate` | - | `boolean registerFixedRate(String jobName, Duration fixedRate, Runnable runnable)` | `LocalJobFacadeAndQueryTest` |
| **操作门面** | 编程式注册固定延迟 | `LocalJobTemplate` | - | `boolean registerFixedDelay(String jobName, Duration fixedDelay, Runnable runnable)` | `LocalJobFacadeAndQueryTest` |
| **操作门面** | 注销并移除任务 | `LocalJobTemplate` | - | `boolean remove(String jobName)`，停止调度并从注册表彻底注销移除任务 | `LocalJobFacadeAndQueryTest`<br>`SampleJobLocalIntegrationTest` |
| **操作门面** | 查询单任务运行状态 | `LocalJobTemplate` | - | `JobInfo getJob(String jobName)`，获取任务定义、运行状态、统计指标与最近日志 | `LocalJobFacadeAndQueryTest`<br>`SampleJobLocalIntegrationTest` |
| **操作门面** | 巡检所有受管任务清单 | `LocalJobTemplate` | - | `List<JobInfo> listJobs()`，获取系统内全量任务运行时清单（非 null 空安全） | `LocalJobFacadeAndQueryTest`<br>`SampleJobLocalIntegrationTest` |
| **操作门面** | 查询最近执行审计日志 | `LocalJobTemplate` | - | `List<JobExecutionRecord> listRecentLogs(String jobName, int limit)`，回查审计记录 | `LocalJobStorageAndLifecycleTest`<br>`DailyReconciliationScenarioJob` |
| **操作门面** | 滚动清理过期历史日志 | `LocalJobTemplate` | `log-retention-days` | `int cleanExpiredLogs(Duration retentionDuration)`，清理过期历史记录 | `LocalJobStorageAndLifecycleTest` |
| **操作门面** | 判断任务是否存在 | `LocalJobTemplate` | - | `boolean exists(String jobName)`，判断指定任务是否已存在于调度注册表中 | `LocalJobFacadeAndQueryTest` |
| **操作门面** | 探测任务是否在执行中 | `LocalJobTemplate` | - | `boolean isExecuting(String jobName)`，判断任务当前是否处于 Worker 活跃运行中 | `LocalJobFacadeAndQueryTest` |
| **操作门面** | Cron 语法合法性校验 | `LocalJobTemplate` | - | `boolean isValidCron(String cronExpression)`，校验 6 位标准 Cron 语法 | `LocalJobFacadeAndQueryTest`<br>`SampleJobLocalIntegrationTest` |
| **操作门面** | Cron 未来触发时序预演 | `LocalJobTemplate` | - | `List<Instant> getNextExecutionTimes(String cron, int count, [Instant fromTime])` | `LocalJobFacadeAndQueryTest`<br>`SampleJobLocalIntegrationTest` |
| **上下文句柄** | 强类型入参 DTO 绑定 | `JobContext` | - | `<T> T bind(Class<T> targetClass)`，反序列化入参并触发 JSR-303 Fail-Fast 校验 | `LocalJobParamValidationTest`<br>`TypedParamArchivingScenarioJob` |
| **上下文句柄** | 结构化任务日志追加 | `JobContext` | - | `context.log(String message)`，在任务执行中记录结构化审计日志并持久化 | `LocalJobFacadeAndQueryTest`<br>`DailyReconciliationScenarioJob` |
| **上下文句柄** | 获取当前任务名称 | `JobContext` | - | `String getJobName()`，返回当前正在执行的任务唯一标识 | `LocalJobFacadeAndQueryTest` |
| **上下文句柄** | 获取链路追踪 ID | `JobContext` | - | `String getTraceId()`，返回自动生成的 32 位无锁全局链路追踪 MDC traceId | `LocalJobTimeoutRetryAndMdcTest`<br>`DailyReconciliationScenarioJob` |
| **上下文句柄** | 获取当前租户标识 | `JobContext` | - | `String getTenantId()`，返回当前调度的租户标识（非租户或 IGNORE 模态为 null） | `LocalJobMultiTenantTest`<br>`MultiTenantBillingScenarioJob` |
| **上下文句柄** | 获取任务启动时间 | `JobContext` | - | `Instant getFireTime()`，返回本次调度的实际触发启动时间戳 | `LocalJobFacadeAndQueryTest` |
| **上下文句柄** | 获取调度自定义参数 | `JobContext` | - | `Map<String, Object> getParams()`，获取手动触发时传入的自定义业务参数 | `LocalJobParamValidationTest`<br>`TypedParamArchivingScenarioJob` |
| **产物模型** | 成功结果快速构建 | `JobResult` | - | `JobResult.success()` / `JobResult.success(msg)` / `JobResult.success(msg, data)` | `LocalJobFacadeAndQueryTest`<br>`DailyReconciliationScenarioJob` |
| **产物模型** | 失败结果快速构建 | `JobResult` | - | `JobResult.failure(String message)`，构建显式失败产物（记录失败状态与告警） | `LocalJobFacadeAndQueryTest`<br>`MultiTenantBillingScenarioJob` |
| **多租户模态** | SaaS 租户隔离枚举 | `JobTenantMode` | - | `IGNORE`（忽略租户）、`PARAM`（入参指定租户）、`ITERATE`（全租户轮询遍历） | `LocalJobMultiTenantTest`<br>`MultiTenantBillingScenarioJob` |
| **多租户发现** | 租户发现服务 SPI | `TenantProvider` | - | `List<String> getTenantIds()`，为 `ITERATE` 模态提供活跃租户标识列表 | `LocalJobMultiTenantTest`<br>`SampleTenantProvider` |
| **集群互斥** | 集群互斥锁 SPI | `JobClusterLockProvider` | `cluster-lock.*` | `JobLockHandle tryLock(String jobName, Duration leaseTime)`，抢锁失败返回 null | `LocalJobClusterLockTest`<br>`ClusterDeduplicationScenarioJob` |
| **集群互斥** | Redis 轻量互斥锁实现 | `RedisJobClusterLockProvider` | `cluster-lock.type=AUTO\|REDIS`<br>`cluster-lock.key-prefix` | 原生 `SET NX PX` 原子抢占，Lua 脚本原子校验属主安全释放，防误删防死锁 | `RedisJobClusterLockProviderTest`<br>`ClusterDeduplicationScenarioJob` |
| **告警防风暴** | 三阶告警聚合器 | `AntiStormJobAlertAggregator` | `alert.anti-storm.*` | 首发即送、滑动窗口累计降噪、阶段聚合汇总、自愈恢复绿色通知 | `AntiStormJobAlertAggregatorTest`<br>`SampleJobLocalIntegrationTest` |
| **告警扩展** | 异常失败告警转发 SPI | `JobAlertHandler` | `alert.enabled`<br>`alert.channel` | `void handleAlert(JobAlertContext context)`，接收告警上下文并转发通知平台 | `LocalJobActuatorAndEventTest`<br>`SampleCustomJobAlertHandler` |
| **监控指标** | Micrometer 指标度量 | `LocalJobMetricsBinder` | `metrics.enabled` | 导出 Timer (`ateng.job.local.execution`)、Counter (`discarded`)、Gauge (`active`) | `LocalJobMetricsTest`<br>`SystemMetricsSamplingScenarioJob` |
| **领域事件** | 任务调度生命周期事件 | `JobEvent` 族 | - | `JobStartingEvent`、`JobSuccessEvent`、`JobFailedEvent`、`JobTimeoutEvent`、`JobDiscardedEvent` | `LocalJobActuatorAndEventTest`<br>`SampleJobEventListener` |
| **存储扩展** | 任务元数据仓储 SPI | `JobMetadataRepository` | `prefer-database-config` | `save` / `findById` / `findAll` / `deleteById`，支持数据库配置仲裁覆盖代码 | `LocalJobStorageAndLifecycleTest`<br>`InMemoryJobMetadataRepository` |
| **存储扩展** | 日志存储与防 OOM SPI | `JobLogStorageProvider` | `in-memory-log-capacity` | `saveRecord` / `findRecentLogs` / `cleanExpiredLogs`，有界环形淘汰防堆溢出 | `LocalJobStorageAndLifecycleTest`<br>`InMemoryJobLogStorageProvider` |
| **运维监控** | 任务管控 Actuator 端点 | `LocalJobEndpoint` | `actuator.enabled` | `/actuator/localjobs`，支持任务巡检、Cron 触发预演、即时触发、暂停与恢复 | `LocalJobActuatorAndEventTest`<br>`SampleJobLocalIntegrationTest` |
| **生命周期** | 延迟就绪与停机排空 | `LocalJobLifecycleManager` | `shutdown-await-termination-period` | Spring `SmartLifecycle` phase=`MAX-100`，容器就绪后激活，退出时排空 Worker | `LocalJobStorageAndLifecycleTest`<br>`SampleJobLocalIntegrationTest` |
| **自动装配** | 模块核心自动装配入口 | `AtengJobLocalAutoConfiguration` | `enabled` | 标准 Spring Boot 3 自动装配，全套 `@ConditionalOnMissingBean` 守卫与显式时序 | `AtengJobLocalAutoConfigurationTest` |

---

## 1. 模块定位与技术基线

`ateng-boot-job-local` 专为单体应用与轻量微服务架构打造，彻底消除对 XXL-JOB、PowerJob、Quartz 等沉重外部服务中心与持久化中间件的强依赖：

* **零膨胀原生内核**：100% 依托 Spring 6 原生 `ThreadPoolTaskScheduler`、JDK 17 LTS 并发原语与 Spring Boot 3.3.13，免除任何第三方调度框架侵入；
* **双层物理隔离防护**：调度引擎将“时钟推进唤醒”与“Worker 业务执行”实施物理线程池级彻底隔离，杜绝因单任务耗时阻塞影响全局任务调度；
* **非 Web 模块零 Controller 契约**：严格遵循框架工程契约，后台调度组件 100% 通过专职业务场景类落地，统一由 `LocalJobTemplate` 或云原生 `/actuator/localjobs` 运维端点直接管控；
* **多租户与微服务集群原生就绪**：内置 SaaS 多租户全模态编排、原生零臃肿 Redis 集群互斥锁、三阶防风暴告警聚合器与云原生 Micrometer 指标度量。

### Maven 坐标引入

在已导入 `ateng-boot-bom` 的工程中，直接引入 Starter 即可（免配 `<version>` 标签）：

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-job-local</artifactId>
</dependency>
```

---

## 2. 最小起步体验 (Happy Path & Out-of-the-Box)

以下配置与代码 100% 逆向提取自已在 CI 与端到端单测全绿跑通的场景样例工程 [`ateng-boot-sample-job-local`](../ateng-boot-samples/ateng-boot-sample-job-local)。

### 步骤一：配置参数 (`application.yml`)

```yaml
ateng:
  boot:
    job:
      local:
        enabled: true
        pool-size: 5
        in-memory-log-capacity: 50
        log-retention-days: 7
        shutdown-await-termination-period: 30s
        actuator:
          enabled: true
        alert:
          enabled: true
          channel: ops-robot

management:
  endpoints:
    web:
      exposure:
        include: health,info,localjobs
  endpoint:
    health:
      show-details: always
```

### 步骤二：声明定时任务组件 (`DailyReconciliationScenarioJob.java`)

```java
package io.github.atengk.boot.sample.job.local.job;

import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DailyReconciliationScenarioJob {

    private static final Logger log = LoggerFactory.getLogger(DailyReconciliationScenarioJob.class);

    @LocalJob(name = "dailyReconciliationJob", description = "每日定时财务对账 Cron 任务", cron = "0 0 2 * * ?")
    public JobResult executeDailyReconciliation(JobContext context) {
        context.log("开始执行对账数据巡检...");
        context.log("对账批次加载完成，匹配记录数: 1200 条");
        log.info("dailyReconciliationJob executed successfully, traceId={}", context.getTraceId());
        return JobResult.success("对账完成，匹配成功: 1200 条，异常 0 条");
    }
}
```

### 步骤三：编程式管理与即时触发 (`LocalJobTemplate`)

在业务服务或测试中注入 `LocalJobTemplate`，即可实现完备对称的运维调度：

```java
@Service
public class ScenarioExecutionService {

    private final LocalJobTemplate localJobTemplate;

    public ScenarioExecutionService(LocalJobTemplate localJobTemplate) {
        this.localJobTemplate = localJobTemplate;
    }

    public void manageJobs() {
        // 1. 手动立即触发执行任务
        JobResult result = localJobTemplate.trigger("dailyReconciliationJob");
        
        // 2. 暂停与恢复任务调度
        localJobTemplate.pause("dailyReconciliationJob");
        localJobTemplate.resume("dailyReconciliationJob");
        
        // 3. 动态注销或更新 Cron 周期
        localJobTemplate.updateCron("dailyReconciliationJob", "0 0 3 * * ?");
        
        // 4. 回查最近执行审计日志
        List<JobExecutionRecord> logs = localJobTemplate.listRecentLogs("dailyReconciliationJob", 10);
    }
}
```

---

## 3. 六大真实业务场景落地实测 (The 6 Business Scenarios)

样例工程已落地 6 大专职业务场景，源码位于 [`io.github.atengk.boot.sample.job.local.job`](../ateng-boot-samples/ateng-boot-sample-job-local/src/main/java/io/github/atengk/boot/sample/job/local/job)：

### 场景一：每日财务对账 (Cron 调度、结构化日志与强类型 JobResult)
* **业务组件**：[`DailyReconciliationScenarioJob.java`](../ateng-boot-samples/ateng-boot-sample-job-local/src/main/java/io/github/atengk/boot/sample/job/local/job/DailyReconciliationScenarioJob.java)
* **技术特性**：演示 6 位标准 Cron 表达式、`JobContext.log` 收集结构化现场日志、自动注入无锁 32 位 MDC `traceId`，并显式返回强类型 `JobResult.success(...)`。

### 场景二：订单超时关单 (FixedDelay、硬超时熔断与指数退避重试)
* **业务组件**：[`OrderTimeoutCancelScenarioJob.java`](../ateng-boot-samples/ateng-boot-sample-job-local/src/main/java/io/github/atengk/boot/sample/job/local/job/OrderTimeoutCancelScenarioJob.java)
* **技术特性**：演示固定延迟 `fixedDelay = "10s"`、硬超时 `timeout = "300ms"` 强行中断 Worker 线程、指数退避重试 `maxRetries = 2`（累计尝试 3 次），并在失败耗尽时触发告警桥接。
```java
@LocalJob(
        name = "orderTimeoutCancelJob",
        description = "订单超时自动关单与库存回补任务",
        fixedDelay = "10s",
        initialDelay = "1h",
        timeout = "300ms",
        maxRetries = 2,
        retryInterval = "50ms"
)
public JobResult executeOrderTimeoutCancel(JobContext context) throws InterruptedException {
    // 业务关单逻辑，若下游耗时超过 300ms 调度器自动触发 Thread.interrupt() 中断并自适应退避重试
    return JobResult.success("扫描并关闭超时订单: 5 笔");
}
```

### 场景三：SaaS 多租户计费 (`JobTenantMode.ITERATE` 与 Fail-Safe 遍历容错)
* **业务组件**：[`MultiTenantBillingScenarioJob.java`](../ateng-boot-samples/ateng-boot-sample-job-local/src/main/java/io/github/atengk/boot/sample/job/local/job/MultiTenantBillingScenarioJob.java)
* **技术特性**：演示声明式全租户轮询模态。调度引擎自动从 `TenantProvider` SPI 获取活跃租户列表，逐个切换 `TenantContextHolder` 并安全调度；若某个租户抛出异常，触发 Fail-Safe 隔离保护，不阻断后续其他租户的调度，并最终聚合返回部分成功统计：
```java
@LocalJob(
        name = "multiTenantBillingJob",
        description = "多租户账单自动化结算与出账任务",
        tenantMode = JobTenantMode.ITERATE,
        fixedDelay = "1h",
        initialDelay = "1h"
)
public JobResult executeMultiTenantBilling(JobContext context) {
    String tenantId = context.getTenantId();
    // 租户级账单核算，上下文已由调度引擎无缝绑定为当前 tenantId
    return JobResult.success("租户 [" + tenantId + "] 账单核算成功");
}
```

### 场景四：系统指标周期采样 (FixedRate 与云原生 Micrometer 监控指标)
* **业务组件**：[`SystemMetricsSamplingScenarioJob.java`](../ateng-boot-samples/ateng-boot-sample-job-local/src/main/java/io/github/atengk/boot/sample/job/local/job/SystemMetricsSamplingScenarioJob.java)
* **技术特性**：演示固定频率 `fixedRate = "5s"` 周期调度与 `String` 返回类型自动包装，调度事件自动推送到 Micrometer `LocalJobMetricsBinder`，输出 `ateng.job.local.execution` Timer 耗时度量。

### 场景五：历史数据冷归档 (强类型 DTO 入参绑定与 JSR-303 Fail-Fast 校验)
* **业务组件**：[`TypedParamArchivingScenarioJob.java`](../ateng-boot-samples/ateng-boot-sample-job-local/src/main/java/io/github/atengk/boot/sample/job/local/job/TypedParamArchivingScenarioJob.java)
* **入参 DTO**：[`ArchivingJobParam.java`](../ateng-boot-samples/ateng-boot-sample-job-local/src/main/java/io/github/atengk/boot/sample/job/local/dto/ArchivingJobParam.java)
* **技术特性**：演示自定义强类型 DTO 与 `JobContext` 组合签名自适应分发；触发入参自动反序列化并触发 JSR-303 注解快速失败校验，校验失败立即阻断执行：
```java
@LocalJob(
        name = "typedParamArchivingJob",
        description = "历史数据冷归档与压缩清理任务",
        fixedDelay = "1h",
        initialDelay = "1h"
)
public JobResult executeArchiving(ArchivingJobParam param, JobContext context) {
    context.log("开始归档业务表 [" + param.getTableName() + "], 保留天数: " + param.getRetentionDays());
    return JobResult.success("表 [" + param.getTableName() + "] 历史记录归档完成");
}
```

### 场景六：分布式集群防重 (轻量 Redis 互斥锁与原子防误删租约)
* **业务组件**：[`ClusterDeduplicationScenarioJob.java`](../ateng-boot-samples/ateng-boot-sample-job-local/src/main/java/io/github/atengk/boot/sample/job/local/job/ClusterDeduplicationScenarioJob.java)
* **技术特性**：声明 `clusterLock = true` 与 `clusterLockLeaseTime = "1m"`。基于 `RedisJobClusterLockProvider` 执行 `SET NX PX` 原子租约抢占与 Lua 脚本属主校验释放，抢锁失败的节点安全跳过并记录丢弃度量。

---

## 4. 生产扩展接管 (Customization & Extensibility)

所有扩展点均基于标准 SPI 与 `@ConditionalOnMissingBean` 契约，支持企业级无缝替换：

### 4.1 原生零臃肿 Redis 轻量集群互斥锁 (`RedisJobClusterLockProvider`)

当工程引入 Spring Data Redis 且包含 `StringRedisTemplate` 时，系统自动装配 `RedisJobClusterLockProvider`（无需依赖重型 Redisson）。若需自定义或强制声明：

```yaml
ateng:
  boot:
    job:
      local:
        cluster-lock:
          enabled: true
          type: REDIS             # AUTO (自动探测降级) / REDIS (强制Redis) / LOCAL (强制单机内存)
          key-prefix: "ateng:job:lock:"
          default-lease-time: 5m
```

底层基于原子 Lua 脚本实施防御性属主安全释放（防止释放非当前节点持有的锁）：
```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
```

### 4.2 SaaS 多租户发现服务 SPI (`TenantProvider`)

在多租户系统微服务中，只需声明一个 `TenantProvider` Bean 即可为 `JobTenantMode.ITERATE` 任务供应当前租户列表：

```java
package io.github.atengk.boot.sample.job.local.provider;

import io.github.atengk.boot.job.local.tenant.TenantProvider;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class SampleTenantProvider implements TenantProvider {

    @Override
    public List<String> getTenantIds() {
        // 从租户管理中台或数据库租户配置中心动态拉取有效租户
        return List.of("tenant-corp-a", "tenant-corp-b", "tenant-corp-c");
    }
}
```

### 4.3 三阶告警防风暴聚合器 (`AntiStormJobAlertAggregator`)

针对高频失败任务可能导致研发群机器人被消息轰炸的痛点，Starter 标配三阶告警聚合器：
1. **首发即送**：任务发生首次失败时立即推送高危告警，保障告警实时性；
2. **滑动窗口累计**：在 `silenceWindow`（默认 5 分钟）静默期内抑制重复告警，仅原子累加错误计数与最近异常上下文；
3. **聚合阶段提醒与自愈恢复**：窗口期满仍未恢复时推送包含统计与累计频次的聚合告警；任务重新调度成功时，自动推送绿色**故障自愈恢复通知**闭环。

可通过实现 `JobAlertHandler` 接管最终的消息渠道推送：

```java
@Component
public class SampleCustomJobAlertHandler implements JobAlertHandler {

    @Override
    public void handleAlert(JobAlertContext context) {
        // 自定义接入企业微信、飞书、钉钉机器人或短信/电话告警平台
    }
}
```

### 4.4 监听 Spring 领域事件总线 (`JobEvent`)

调度引擎在调度各生命周期节点广播强类型领域事件：

```java
@Component
public class SampleJobEventListener {

    @EventListener
    public void onJobStarting(JobStartingEvent event) {
        // 捕获任务启动：event.getJobName(), event.getTraceId()
    }

    @EventListener
    public void onJobSuccess(JobSuccessEvent event) {
        // 捕获执行成功：event.getDurationMs(), event.getResult()
    }

    @EventListener
    public void onJobFailed(JobFailedEvent event) {
        // 捕获执行失败：event.getErrorMsg(), event.getRetryCount()
    }

    @EventListener
    public void onJobTimeout(JobTimeoutEvent event) {
        // 捕获硬超时熔断：event.getTimeout()
    }

    @EventListener
    public void onJobDiscarded(JobDiscardedEvent event) {
        // 捕获并发丢弃或集群抢锁失败：event.getReason()
    }
}
```

---

## 5. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

1. **时钟调度与 Worker 执行物理隔离**：
   * 专用调度器 `ThreadPoolTaskScheduler`（池大小由 `ateng.boot.job.local.pool-size` 控制）仅负责时钟推进；
   * 业务逻辑在受管 `localJobWorkerExecutor` 线程池中独立执行，单任务慢执行或挂起绝不卡死全局时钟。
2. **四阶重叠并发防卫策略 (`OverlappingStrategy`)**：
   * `DISCARD`（默认防雪崩）：前序未完成时安全跳过当前周期，发布 `JobDiscardedEvent` 并累加指标；
   * `QUEUE`（平滑排队）：后序任务在细粒度条带锁处等待，前序完成后按序执行；
   * `CONCURRENT`（并行处理）：不同周期在独立线程并发并行执行；
   * `INTERRUPT`（打断前序）：新周期到达时向前序执行线程发送 `Thread.interrupt()` 中断信号。
3. **有界队列 FIFO 环形淘汰防堆 OOM**：
   * 默认实现 `InMemoryJobLogStorageProvider` 为每个任务分配固定容量的有界环形队列（由 `in-memory-log-capacity` 控制，默认 100 条）；
   * 超出上限时自动淘汰最旧记录；同时支持 `cleanExpiredLogs(Duration)` 基于时间窗口滚动清理历史，彻底杜绝内存泄漏。
4. **32 位无锁 MDC `traceId` 全链路透传**：
   * 每次任务触发均基于 `fastSimpleUUID()` 自动生成 `traceId` 并注入 MDC；
   * 依托 Core 模块的 `ContextCopyingTaskDecorator` 与 `AsyncContext` 设施，实现 $\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$ 四维上下文全链路透传，并在 `finally` 强制清理现场。
5. **确定性停机排空 (`SmartLifecycle`)**：
   * 实现 Spring `SmartLifecycle`（phase = `Integer.MAX_VALUE - 100`）；
   * 容器退出时首先停止时钟调度器，阻止新周期排队；随后等待已在运行中的 Worker 任务执行完毕（超时时间由 `shutdown-await-termination-period` 控制，默认 30s）。
6. **云原生 Micrometer 监控指标度量**：
   * 自动注册 Timer (`ateng.job.local.execution`，多维标签 `jobName`、`status`、`triggerType`)；
   * 自动注册 Counter (`ateng.job.local.discarded`，标签 `jobName`、`reason`、`triggerType`)；
   * 自动注册 Gauge (`ateng.job.local.active`，实时监测系统活跃 Worker 线程数)。

---

## 6. Actuator 运维管控端点实测交互 (`/actuator/localjobs`)

以下交互数据真实截取自本地端到端集成测试实测控制台：

### 6.1 巡检全量受管任务清单 (`GET /actuator/localjobs`)

```bash
curl -X GET "http://localhost:8080/actuator/localjobs"
```

响应示例：
```json
{
  "status": "UP",
  "total": 6,
  "jobs": [
    {
      "jobName": "dailyReconciliationJob",
      "triggerType": "CRON",
      "status": "RUNNING",
      "cron": "0 0 2 * * ?",
      "fixedRate": null,
      "fixedDelay": null,
      "strategy": "DISCARD",
      "description": "每日定时财务对账 Cron 任务",
      "lastStatus": "SUCCESS",
      "lastResultMsg": "对账完成，匹配成功: 1200 条，异常 0 条"
    },
    {
      "jobName": "multiTenantBillingJob",
      "triggerType": "FIXED_DELAY",
      "status": "RUNNING",
      "strategy": "DISCARD",
      "tenantMode": "ITERATE",
      "description": "多租户账单自动化结算与出账任务",
      "lastStatus": "SUCCESS"
    }
  ]
}
```

### 6.2 Cron 表达式未来触发时序预演 (`GET /actuator/localjobs?cron=...&count=...`)

在不注册任务的情况下，支持通过运维端点对任意 Cron 表达式进行语法校验与未来时钟点预演：

```bash
curl -X GET "http://localhost:8080/actuator/localjobs?cron=0+0+2+*+*+?&count=3"
```

响应示例：
```json
{
  "cron": "0 0 2 * * ?",
  "valid": true,
  "nextExecutionTimes": [
    "2026-11-02T02:00:00Z",
    "2026-11-03T02:00:00Z",
    "2026-11-04T02:00:00Z"
  ]
}
```

### 6.3 单任务详情与最近执行历史日志 (`GET /actuator/localjobs/dailyReconciliationJob`)

```bash
curl -X GET "http://localhost:8080/actuator/localjobs/dailyReconciliationJob"
```

响应示例：
```json
{
  "status": "OK",
  "job": {
    "jobName": "dailyReconciliationJob",
    "triggerType": "CRON",
    "status": "RUNNING",
    "cron": "0 0 2 * * ?",
    "description": "每日定时财务对账 Cron 任务",
    "lastStatus": "SUCCESS"
  },
  "isExecuting": false,
  "nextExecutionTimes": [
    "2026-11-02T02:00:00Z"
  ],
  "recentLogs": [
    {
      "jobName": "dailyReconciliationJob",
      "traceId": "9ed454e2dbea7c2c5d884bf42f735627",
      "triggerType": "MANUAL",
      "status": "SUCCESS",
      "resultMsg": "对账完成，匹配成功: 1200 条，异常 0 条",
      "logs": [
        "[2026-11-01T19:15:03.808Z] 开始执行第 [1] 次对账数据巡检...",
        "[2026-11-01T19:15:03.809Z] 对账批次加载完成，匹配记录数: 1200 条"
      ]
    }
  ]
}
```

### 6.4 动态运维管控：即时触发、暂停与恢复 (`POST /actuator/localjobs/{jobName}`)

1. **手动立即触发执行一次**：
   ```bash
   curl -X POST "http://localhost:8080/actuator/localjobs/dailyReconciliationJob" \
     -H "Content-Type: application/json" \
     -d '{"action": "trigger"}'
   ```
   响应：`{"jobName":"dailyReconciliationJob","action":"trigger","success":true,"message":"对账完成，匹配成功: 1200 条，异常 0 条"}`

2. **暂停任务自动调度**：
   ```bash
   curl -X POST "http://localhost:8080/actuator/localjobs/dailyReconciliationJob" \
     -H "Content-Type: application/json" \
     -d '{"action": "pause"}'
   ```
   响应：`{"jobName":"dailyReconciliationJob","action":"pause","success":true,"message":"Job [dailyReconciliationJob] paused successfully"}`

3. **恢复任务自动调度**：
   ```bash
   curl -X POST "http://localhost:8080/actuator/localjobs/dailyReconciliationJob" \
     -H "Content-Type: application/json" \
     -d '{"action": "resume"}'
   ```
   响应：`{"jobName":"dailyReconciliationJob","action":"resume","success":true,"message":"Job [dailyReconciliationJob] resumed successfully"}`
