# ateng-boot-job-local 基础设施 Starter

> 面向单机定时调度与轻量分布式集群防重的生产级调度基础设施 Starter，坚持纯原生零重型臃肿依赖设计，基于 Spring `ThreadPoolTaskScheduler` 与受管 Worker 线程池提供声明式 `@LocalJob` 注解与对称编程式门面 `LocalJobTemplate`、四阶重叠并发防卫策略（`OverlappingStrategy`）、硬超时强中断熔断与指数退避重试、32 位无锁 MDC `traceId` 全链路追踪、两级配置加载与有界环形淘汰防堆 OOM（`JobLogStorageProvider`）、防死锁租约轻量集群互斥锁（`JobClusterLockProvider`）、`SmartLifecycle` 确定性停机排空、`/actuator/localjobs` 运维管控端点、Spring 领域事件总线及群机器人告警桥接设施。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.job.local.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **声明式调度** | 声明式任务注解 | `@LocalJob` | `enabled` | `@LocalJob(...)` 标注于 Spring 受管 Bean 方法，自动扫描并纳管调度 | `LocalJobSchedulingTest` / `SampleScheduledJobs` |
| **声明式调度** | 任务标识名称 | `@LocalJob.value` / `name` | - | `String name()`，任务唯一标识，缺省为空时自动推导为 `类名#方法名` | `LocalJobSchedulingTest` / `SampleScheduledJobs` |
| **声明式调度** | 标准 Cron 表达式 | `@LocalJob.cron` | - | `String cron()`，支持标准 Spring 6 位 Cron 表达式（如 `0 0 2 * * ?`） | `LocalJobSchedulingTest` / `SampleScheduledJobs` |
| **声明式调度** | 固定频率调度 | `@LocalJob.fixedRate` | - | `String fixedRate()`，强类型时间字符串（如 `5s`、`1m`），上次开始到本次开始的间隔 | `LocalJobSchedulingTest` / `SampleScheduledJobs` |
| **声明式调度** | 固定延迟调度 | `@LocalJob.fixedDelay` | - | `String fixedDelay()`，强类型时间字符串（如 `10s`、`5m`），上次结束到本次开始的间隔 | `LocalJobSchedulingTest` / `SampleScheduledJobs` |
| **声明式调度** | 初始启动延迟 | `@LocalJob.initialDelay` | - | `String initialDelay()`，强类型时间字符串（如 `5s`、`1h`），容器启动后的首次调度延迟 | `LocalJobSchedulingTest` / `SampleScheduledJobs` |
| **声明式调度** | 并发重叠防卫策略 | `@LocalJob.strategy` | - | `OverlappingStrategy strategy()`，默认 `DISCARD`，支持 `QUEUE` / `CONCURRENT` / `INTERRUPT` | `LocalJobOverlappingAndFacadeTest` / `SampleScheduledJobs` |
| **声明式调度** | 单次执行硬超时阈值 | `@LocalJob.timeout` | - | `String timeout()`，单次执行超时强行中断阈值（如 `300ms`、`1m`），缺省不限时 | `LocalJobTimeoutRetryAndMdcTest` / `SampleScheduledJobs` |
| **声明式调度** | 失败指数重试次数 | `@LocalJob.maxRetries` | - | `int maxRetries()`，任务异常失败后最大自适应重试次数，默认 0 不重试 | `LocalJobTimeoutRetryAndMdcTest` / `SampleScheduledJobs` |
| **声明式调度** | 失败重试退避间隔 | `@LocalJob.retryInterval` | - | `String retryInterval()`，重试退避初始间隔（默认 `1s`），联动 `RetryUtil` 指数退避 | `LocalJobTimeoutRetryAndMdcTest` / `SampleScheduledJobs` |
| **声明式调度** | 轻量集群互斥防重 | `@LocalJob.clusterLock` | - | `boolean clusterLock()`，是否开启多节点集群互斥锁，抢锁失败安全跳过，默认 false | `LocalJobClusterLockTest` / `SampleScheduledJobs` |
| **声明式调度** | 集群互斥锁租约时长 | `@LocalJob.clusterLockLeaseTime` | - | `String clusterLockLeaseTime()`，防死锁安全租约时长（默认 `5m`） | `LocalJobClusterLockTest` / `SampleScheduledJobs` |
| **声明式调度** | 任务人读描述 | `@LocalJob.description` | - | `String description()`，任务中文业务说明，用于 Actuator 与看板展示 | `LocalJobActuatorAndEventTest` / `SampleScheduledJobs` |
| **声明式调度** | 任务静态启用开关 | `@LocalJob.enabled` | - | `boolean enabled()`，单任务静态启动开关，默认 true | `LocalJobSchedulingTest` |
| **操作门面** | 手动无参立即触发 | `LocalJobTemplate` | - | `JobResult trigger(String jobName)`，手动立即触发执行一次指定任务 | `LocalJobOverlappingAndFacadeTest` / `JobSampleController` |
| **操作门面** | 手动带参立即触发 | `LocalJobTemplate` | - | `JobResult trigger(String jobName, Map<String, Object> params)`，携带自定义参数触发 | `LocalJobOverlappingAndFacadeTest` / `SampleJobLocalIntegrationTest` |
| **操作门面** | 暂停周期性调度 | `LocalJobTemplate` | - | `boolean pause(String jobName)`，暂停指定任务的自动触发，状态变更为 `PAUSED` | `LocalJobOverlappingAndFacadeTest` / `JobSampleController` |
| **操作门面** | 恢复周期性调度 | `LocalJobTemplate` | - | `boolean resume(String jobName)`，恢复暂停的任务，重新挂载至时钟调度池 | `LocalJobOverlappingAndFacadeTest` / `JobSampleController` |
| **操作门面** | 动态修改 Cron 周期 | `LocalJobTemplate` | - | `boolean updateCron(String jobName, String cronExpression)`，动态重置周期并立即重调度 | `LocalJobOverlappingAndFacadeTest` / `SampleJobLocalIntegrationTest` |
| **操作门面** | 动态修改固定频率周期 | `LocalJobTemplate` | - | `boolean updateFixedRate(String jobName, Duration newFixedRate)`，动态修改频率并重调度 | `LocalJobOverlappingAndFacadeTest` |
| **操作门面** | 动态修改固定延迟周期 | `LocalJobTemplate` | - | `boolean updateFixedDelay(String jobName, Duration newFixedDelay)`，动态修改延迟并重调度 | `LocalJobOverlappingAndFacadeTest` |
| **操作门面** | 编程式注册通用任务 | `LocalJobTemplate` | - | `boolean register(JobDefinition definition, JobHandler handler)`，完整元数据注册 | `LocalJobOverlappingAndFacadeTest` |
| **操作门面** | 编程式极简注册 Cron | `LocalJobTemplate` | - | `boolean registerCron(String jobName, String cron, Runnable runnable)`，快捷注册 Cron 任务 | `LocalJobOverlappingAndFacadeTest` / `JobSampleController` |
| **操作门面** | 编程式注册固定频率 | `LocalJobTemplate` | - | `boolean registerFixedRate(String jobName, Duration fixedRate, Runnable runnable)` | `LocalJobOverlappingAndFacadeTest` |
| **操作门面** | 编程式注册固定延迟 | `LocalJobTemplate` | - | `boolean registerFixedDelay(String jobName, Duration fixedDelay, Runnable runnable)` | `LocalJobOverlappingAndFacadeTest` |
| **操作门面** | 注销并移除任务 | `LocalJobTemplate` | - | `boolean remove(String jobName)`，停止调度并从注册表彻底移除任务 | `LocalJobOverlappingAndFacadeTest` |
| **操作门面** | 查询单任务运行状态 | `LocalJobTemplate` | - | `JobInfo getJob(String jobName)`，获取任务定义、运行状态、统计指标与最近日志 | `LocalJobActuatorAndEventTest` / `JobSampleController` |
| **操作门面** | 巡检所有受管任务清单 | `LocalJobTemplate` | - | `List<JobInfo> listJobs()`，获取系统内全量任务运行时清单（保证非 null 空安全） | `LocalJobActuatorAndEventTest` / `JobSampleController` |
| **操作门面** | 查询最近执行审计日志 | `LocalJobTemplate` | - | `List<JobExecutionRecord> listRecentLogs(String jobName, int limit)`，回查审计记录 | `LocalJobStorageAndLifecycleTest` / `JobSampleController` |
| **操作门面** | 滚动清理过期历史日志 | `LocalJobTemplate` | `log-retention-days` | `int cleanExpiredLogs(Duration retentionDuration)`，手动或周期清理过期历史记录 | `LocalJobStorageAndLifecycleTest` |
| **上下文句柄** | 结构化任务日志追加 | `JobContext` | - | `context.log(String message)`，在任务执行过程中记录结构化执行日志并持久化 | `LocalJobOverlappingAndFacadeTest` / `SampleScheduledJobs` |
| **上下文句柄** | 获取当前任务名称 | `JobContext` | - | `String getJobName()`，返回当前正在执行的任务标识 | `LocalJobOverlappingAndFacadeTest` |
| **上下文句柄** | 获取链路追踪 ID | `JobContext` | - | `String getTraceId()`，返回自动生成的 32 位无锁全局链路追踪 MDC traceId | `LocalJobTimeoutRetryAndMdcTest` / `SampleScheduledJobs` |
| **上下文句柄** | 获取任务启动时间 | `JobContext` | - | `Instant getFireTime()`，返回本次调度的实际触发启动时间戳 | `LocalJobOverlappingAndFacadeTest` |
| **上下文句柄** | 获取调度自定义参数 | `JobContext` | - | `Map<String, Object> getParams()`，获取手动触发时传入的自定义业务参数 | `LocalJobOverlappingAndFacadeTest` |
| **上下文句柄** | 读取已记录日志列表 | `JobContext` | - | `List<String> getLogs()`，返回当前任务已记录的上下文日志集合 | `LocalJobOverlappingAndFacadeTest` |
| **产物模型** | 成功结果快速构建 | `JobResult` | - | `JobResult.success()` / `JobResult.success(String message)` / `JobResult.success(msg, data)` | `LocalJobOverlappingAndFacadeTest` / `SampleScheduledJobs` |
| **产物模型** | 失败结果快速构建 | `JobResult` | - | `JobResult.failure(String message)`，构建显式失败产物（不抛异常时记录失败状态） | `LocalJobOverlappingAndFacadeTest` |
| **集群互斥** | 集群互斥锁 SPI | `JobClusterLockProvider` | - | `JobLockHandle tryLock(String jobName, Duration leaseTime)`，抢锁失败返回 null | `LocalJobClusterLockTest` / `LocalJobClusterLockProvider` |
| **集群互斥** | 锁持有生命周期句柄 | `JobLockHandle` | - | `close()` 释放锁 / `isExpired()` 探测是否失效 / `getOwner()` 属主签名校验 | `LocalJobClusterLockTest` |
| **存储扩展** | 任务元数据仓储 SPI | `JobMetadataRepository` | `prefer-database-config` | `save` / `findById` / `findAll` / `deleteById`，支持数据库配置仲裁覆盖静态代码 | `LocalJobStorageAndLifecycleTest` / `InMemoryJobMetadataRepository` |
| **存储扩展** | 日志存储与防 OOM SPI | `JobLogStorageProvider` | `in-memory-log-capacity` | `saveRecord` / `findRecentLogs` / `cleanExpiredLogs`，有界环形淘汰防堆溢出 | `LocalJobStorageAndLifecycleTest` / `InMemoryJobLogStorageProvider` |
| **告警扩展** | 异常失败告警转发 SPI | `JobAlertHandler` | `alert.enabled`<br>`alert.channel` | `void handleAlert(JobAlertContext context)`，接收告警上下文并转发群机器人 | `LocalJobActuatorAndEventTest` / `NotificationJobAlertHandler` |
| **领域事件** | 任务启动开始事件 | `JobStartingEvent` | - | 任务被调度触发或手动触发启动时发布，携带 `jobName` 与 `traceId` | `LocalJobActuatorAndEventTest` / `SampleJobEventListener` |
| **领域事件** | 任务执行成功事件 | `JobSuccessEvent` | - | 任务单次执行成功完毕后发布，携带执行耗时 `durationMs` 与执行成果 `result` | `LocalJobActuatorAndEventTest` / `SampleJobEventListener` |
| **领域事件** | 任务执行失败事件 | `JobFailedEvent` | - | 任务抛出未受控异常失败后发布，携带最终异常 `throwable` 与重试次数 | `LocalJobActuatorAndEventTest` / `SampleJobEventListener` |
| **领域事件** | 任务硬超时熔断事件 | `JobTimeoutEvent` | - | 任务执行超过 `timeout` 阈值被强行中断后发布，携带配置超时阈值 `timeout` | `LocalJobActuatorAndEventTest` / `SampleJobEventListener` |
| **领域事件** | 任务并发丢弃事件 | `JobDiscardedEvent` | - | 采用 `DISCARD` 策略时因前序任务未完成被丢弃时发布，记录丢弃现场 | `LocalJobActuatorAndEventTest` / `SampleJobEventListener` |
| **运维监控** | 任务清单状态巡检端点 | `LocalJobEndpoint` | `actuator.enabled` | `GET /actuator/localjobs`，全量受管任务清单与调度状态巡检 | `LocalJobActuatorAndEventTest` / `SampleJobLocalIntegrationTest` |
| **运维监控** | 单任务详情与日志端点 | `LocalJobEndpoint` | `actuator.enabled` | `GET /actuator/localjobs/{jobName}`，单任务元数据、执行统计与审计日志回查 | `LocalJobActuatorAndEventTest` / `SampleJobLocalIntegrationTest` |
| **运维监控** | 动态运维管控端点 | `LocalJobEndpoint` | `actuator.enabled` | `POST /actuator/localjobs/{jobName}`，支持 `{"action":"trigger\|pause\|resume"}` | `LocalJobActuatorAndEventTest` / `SampleJobLocalIntegrationTest` |
| **生命周期** | 延迟就绪与停机排空 | `LocalJobLifecycleManager` | `shutdown-await-termination-period` | Spring `SmartLifecycle` phase=`MAX-100`，就绪后激活时钟，停机时排空 Worker | `LocalJobStorageAndLifecycleTest` |
| **自动装配** | 模块自动装配入口 | `AtengJobLocalAutoConfiguration` | `enabled` | Spring Boot 3 标准自动装配，标配 `@AutoConfiguration` 与全套缺省 Bean 守卫 | `AtengJobLocalAutoConfigurationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-job-local` 是专为单机应用与轻量分布式微服务集群打造的高可用、高韧性定时任务基础设施：
- **纯原生零外部依赖**：100% 依托 Spring Boot 3 与 JDK 17 原生能力，零引入 XXL-JOB、PowerJob、Quartz 等庞大外部组件，毫秒级极速装配；
- **声明式与编程式对称完备**：既支持注解驱动开箱即用，又提供 `LocalJobTemplate` 满足管理中台动态增删改查、即时触发与启停诉求；
- **四阶重叠并发防卫**：提供 `DISCARD`（防雪崩跳过）、`QUEUE`（平滑排队）、`CONCURRENT`（并发并行）、`INTERRUPT`（打断前序）四大策略；
- **硬超时熔断与自适应重试**：单次任务支持硬超时截断中断，联动 Core 底座 `RetryUtil` 支持自适应指数退避重试；
- **32 位无锁全链路追踪**：每次触发自动生成 MDC `traceId`，依托 `ContextCopyingTaskDecorator` 与 `AsyncContext` 实现四维上下文透传；
- **内存防 OOM 与集群互斥锁**：有界队列 FIFO 环形淘汰保障堆内存永不溢出；集群互斥锁防死锁租约机制杜绝多副本重复执行；
- **云原生运维度量与多端告警**：内置 `/actuator/localjobs` 运维端点与 Spring 领域事件总线，无缝联动群机器人发送故障告警。

### Maven 坐标引入

在已导入 `ateng-boot-bom` 的工程中，无需声明版本号即可直接引入：

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-job-local</artifactId>
</dependency>
```

---

## 2. 最小起步体验 (Happy Path & Out-of-the-Box)

以下示例代码 100% 逆向提取自已在 CI 与单测全绿跑通的场景样例工程 [`ateng-boot-sample-job-local`](../ateng-boot-samples/ateng-boot-sample-job-local)。

### 步骤一：配置应用属性 (`application.yml`)

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
```

### 步骤二：声明定时任务 (`SampleScheduledJobs.java`)

```java
package io.github.atengk.boot.sample.job.local.job;

import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.enums.OverlappingStrategy;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SampleScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(SampleScheduledJobs.class);

    /**
     * 每日定时健康对账 Cron 任务（演示 JobContext 注入、结构化内存日志记录与强类型 JobResult 返回）
     */
    @LocalJob(name = "sampleCronJob", description = "每日定时健康对账 Cron 任务", cron = "0 0 2 * * ?")
    public JobResult executeDailyReconciliation(JobContext context) {
        context.log("开始执行对账数据巡检...");
        context.log("对账批次加载完成，匹配记录数: 1200 条");
        log.info("sampleCronJob executed successfully, traceId={}", context.getTraceId());
        return JobResult.success("对账完成，匹配成功: 1200 条，异常 0 条");
    }

    /**
     * 硬超时熔断与指数退避重试演示任务
     */
    @LocalJob(name = "sampleTimeoutRetryJob", description = "硬超时熔断与重试演示任务",
            timeout = "300ms", maxRetries = 2, retryInterval = "50ms",
            fixedDelay = "1h", initialDelay = "1h")
    public void executeTimeoutRetryJob() throws InterruptedException {
        Thread.sleep(1000); // 模拟耗时超过 300ms 触发硬超时强中断
    }

    /**
     * 轻量集群互斥防死锁租约任务
     */
    @LocalJob(name = "sampleClusterLockJob", description = "轻量集群互斥防死锁租约任务",
            clusterLock = true, clusterLockLeaseTime = "1m",
            fixedDelay = "1h", initialDelay = "1h")
    public String executeClusterLockJob() {
        return "集群互斥锁获取成功并执行完毕";
    }
}
```

### 步骤三：编程式管理与手动触发 (`JobSampleController.java`)

```java
@RestController
@RequestMapping("/api/v1/jobs")
public class JobSampleController {

    private final LocalJobTemplate localJobTemplate;

    public JobSampleController(LocalJobTemplate localJobTemplate) {
        this.localJobTemplate = localJobTemplate;
    }

    // 1. 手动立即触发指定任务
    @PostMapping("/{jobName}/trigger")
    public Result<JobResult> triggerJob(@PathVariable String jobName) {
        JobResult result = localJobTemplate.trigger(jobName);
        return Result.success(result);
    }

    // 2. 暂停定时任务
    @PostMapping("/{jobName}/pause")
    public Result<Boolean> pauseJob(@PathVariable String jobName) {
        return Result.success(localJobTemplate.pause(jobName));
    }

    // 3. 恢复定时任务
    @PostMapping("/{jobName}/resume")
    public Result<Boolean> resumeJob(@PathVariable String jobName) {
        return Result.success(localJobTemplate.resume(jobName));
    }

    // 4. 查询最近执行历史日志
    @GetMapping("/{jobName}/logs")
    public Result<List<JobExecutionRecord>> listRecentLogs(@PathVariable String jobName) {
        return Result.success(localJobTemplate.listRecentLogs(jobName, 10));
    }
}
```

---

## 3. 生产扩展接管 (Customization & Extensibility)

框架内部所有核心组件均通过 `@Bean @ConditionalOnMissingBean` 暴露，满足企业级无缝扩展：

### 3.1 自定义集群互斥锁 SPI（接入 Redis / Redisson）

默认提供纯内存单机保底实现 `LocalJobClusterLockProvider`。在多节点集群环境下，可实现 `JobClusterLockProvider` SPI 接入 Redis：

```java
@Component
public class RedisJobClusterLockProvider implements JobClusterLockProvider {

    private final RedissonClient redissonClient;

    public RedisJobClusterLockProvider(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public JobLockHandle tryLock(String jobName, Duration leaseTime) {
        String lockKey = "ateng:boot:job:lock:" + jobName;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            boolean acquired = lock.tryLock(0, leaseTime.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                return null;
            }
            return new JobLockHandle() {
                @Override
                public void close() {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }

                @Override
                public boolean isExpired() {
                    return !lock.isLocked();
                }

                @Override
                public String getOwner() {
                    return "redisson-" + Thread.currentThread().getName();
                }
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
```

### 3.2 监听 Spring 领域事件扩展业务

```java
@Component
public class CustomJobEventListener {

    @EventListener
    public void onJobSuccess(JobSuccessEvent event) {
        log.info("任务 [{}] 执行成功，耗时 {} ms，产物: {}",
                event.getJobName(), event.getDurationMs(), event.getResult());
    }

    @EventListener
    public void onJobFailed(JobFailedEvent event) {
        log.error("任务 [{}] 执行失败，重试次数: {}，错误: {}",
                event.getJobName(), event.getRetryCount(), event.getErrorMsg());
    }

    @EventListener
    public void onJobTimeout(JobTimeoutEvent event) {
        log.warn("任务 [{}] 硬超时熔断，阈值: {}",
                event.getJobName(), event.getTimeout());
    }
}
```

### 3.3 自定义告警处理器接管 (`JobAlertHandler`)

实现 `JobAlertHandler` 接口并声明为 Spring Bean，即可替换默认日志告警：

```java
@Component
public class CustomJobAlertHandler implements JobAlertHandler {

    @Override
    public void handleAlert(JobAlertContext context) {
        // 自定义短信、邮件或监控平台集成
        log.warn("收到任务告警 [jobName={}, type={}, error={}]",
                context.getJobName(), context.getAlertType(), context.getErrorMsg());
    }
}
```

---

## 4. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

1. **时钟调度与 Worker 执行物理隔离**：
   - 专用时钟调度线程池 `ThreadPoolTaskScheduler`（默认配置 `ateng.boot.job.local.pool-size`）仅用于时间轮推进与任务唤醒；
   - 实际业务逻辑在受管 Worker 线程池中异步执行，任务内部慢执行或死循环绝不阻塞其他任务的按时调度。
2. **四阶重叠并发防卫策略与细粒度条带锁**：
   - `DISCARD`（默认防雪崩）：前序未完成时安全跳过当前周期，发布 `JobDiscardedEvent` 并记录跳过审计；
   - `QUEUE`（排队执行）：后序任务在细粒度条带互斥锁处等待，前序释放后按序执行；
   - `CONCURRENT`（并行处理）：不同周期在独立线程同时执行；
   - `INTERRUPT`（打断前序）：新周期到达时向前序执行线程发送 `Thread.interrupt()` 中断信号。
3. **有界队列 FIFO 环形淘汰防堆 OOM**：
   - 默认实现 `InMemoryJobLogStorageProvider` 针对每个任务分配固定容量有界队列（默认 `max-retained-records-per-job: 100`）；
   - 超出上限时自动淘汰最旧记录；同时支持 `cleanExpiredLogs(Duration)` 基于时间窗口滚动清理历史，彻底杜绝内存溢出。
4. **集群互斥锁防死锁与租约属主校验**：
   - 互斥锁必须显式配置租约时长（默认 `5m`），即使执行节点意外崩溃宕机，租约到期后自动物理失效；
   - 锁释放时执行属主校验，防止慢节点误释放其他健康节点刚刚获取的新锁。
5. **32 位无锁 MDC traceId 全链路透传**：
   - 每次任务调度均自动生成 `traceId` 并写入 MDC；
   - 依托 Core 模块的 `ContextCopyingTaskDecorator` 与 `AsyncContext` 设施，实现 $\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$ 完整上下文透传，并在 `finally` 强制安全清理。
6. **确定性停机排空 (Graceful Shutdown)**：
   - 实现 Spring `SmartLifecycle`（phase = `Integer.MAX_VALUE - 100`）；
   - 应用退出时首先停止时钟调度器，阻止新周期排队；随后等待已在运行中的 Worker 任务执行完毕（超时时间由 `shutdown-wait-time` 控制，默认 30s）。

---

## 5. Actuator 运维管控与 REST 交互实测

以下输出均真实截取自本地端到端集成测试实测控制台：

### 5.1 巡检所有任务清单 (`GET /actuator/localjobs`)

```bash
curl -X GET "http://localhost:8080/actuator/localjobs"
```

响应示例：
```json
{
  "status": "UP",
  "total": 8,
  "jobs": [
    {
      "jobName": "sampleCronJob",
      "triggerType": "CRON",
      "status": "RUNNING",
      "cron": "0 0 2 * * ?",
      "fixedRate": null,
      "fixedDelay": null,
      "strategy": "DISCARD",
      "description": "每日定时健康对账 Cron 任务",
      "lastStatus": "SUCCESS",
      "lastResultMsg": "对账完成，匹配成功: 1200 条，异常 0 条"
    },
    {
      "jobName": "sampleTimeoutRetryJob",
      "triggerType": "FIXED_DELAY",
      "status": "RUNNING",
      "timeout": "PT0.3S",
      "maxRetries": 2,
      "strategy": "DISCARD",
      "description": "硬超时熔断与重试演示任务"
    }
  ]
}
```

### 5.2 单任务详情与历史日志查询 (`GET /actuator/localjobs/sampleCronJob`)

```bash
curl -X GET "http://localhost:8080/actuator/localjobs/sampleCronJob"
```

响应示例：
```json
{
  "status": "OK",
  "job": {
    "jobName": "sampleCronJob",
    "triggerType": "CRON",
    "status": "RUNNING",
    "cron": "0 0 2 * * ?",
    "description": "每日定时健康对账 Cron 任务",
    "lastStatus": "SUCCESS"
  },
  "recentLogs": [
    {
      "id": "ce16f9cf7f6c3a7eaf217315a540201c",
      "jobName": "sampleCronJob",
      "traceId": "d09af7ffe16bb5d8ded2c04a27fb399d",
      "triggerType": "MANUAL",
      "durationMs": 9,
      "status": "SUCCESS",
      "resultMsg": "对账完成，匹配成功: 1200 条，异常 0 条",
      "retryCount": 0,
      "logs": [
        "[2026-10-25T14:17:49.817905400Z] 开始执行第 [1] 次对账数据巡检...",
        "[2026-10-25T14:17:49.820090900Z] 对账批次加载完成，匹配记录数: 1200 条"
      ]
    }
  ]
}
```

### 5.3 Actuator 手动立即触发任务 (`POST /actuator/localjobs/sampleCronJob`)

```bash
curl -X POST "http://localhost:8080/actuator/localjobs/sampleCronJob" \
  -H "Content-Type: application/json" \
  -d '{"action": "trigger"}'
```

响应示例：
```json
{
  "jobName": "sampleCronJob",
  "action": "trigger",
  "success": true,
  "message": "对账完成，匹配成功: 1200 条，异常 0 条"
}
```
