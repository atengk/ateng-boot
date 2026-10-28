# ateng-boot-sample-job-local 单机定时任务自验工程

> 专注于工业级单机定时任务与轻量调度基础设施（声明式 `@LocalJob` 注解、四阶重叠并发防卫 `OverlappingStrategy`、硬超时熔断与指数退避重试、MDC 全链路追踪、有界环形淘汰防 OOM、轻量集群互斥防死锁租约、Spring 领域事件与群机器人告警、Actuator 运维管控端点）。

---

## 1. 场景痛点与功能定位

在现代化微服务与单体应用开发中，轻量级定时任务是业务对账、缓存预热、状态轮询、报表聚合的第一选择。然而使用 Spring 原生 `@Scheduled` 或传统调度工具普遍面临以下痛点：
- **无法动态增删与启停**：原生 `@Scheduled` 方法在应用启动后固化，无法在运行时动态启停、修改 Cron 或按需手动触发一次；
- **并发重叠引发雪崩**：长耗时任务在周期到达时若未结束，默认单线程下会堵塞后续所有任务，多线程下又会并发重复执行引发死锁或数据脏写；
- **缺乏超时控制与失败重试**：慢任务陷入死循环或下游 RPC 假死时无法自动中断，失败时缺乏指数退避重试安全网；
- **上下文丢失与无痕排查**：定时任务在后台异步触发，缺乏统一的 MDC `traceId`，线上偶发失败时日志散落各处难以关联追踪；
- **内存溢出与集群多节点重复执行**：任务执行日志若无限制累积极易导致内存泄漏或堆 OOM；多副本部署时单机任务在所有节点同时触发引发竞态冲突。

`ateng-boot-sample-job-local` 作为 1:1 对齐 `ateng-boot-job-local` 的场景样例工程，提供真实端到端可运行沙箱：
- **声明式与编程式双模驱动**：支持 `@LocalJob` 注解与 `LocalJobTemplate` 对称门面，支持注入 `JobContext` 记录结构化日志与返回强类型 `JobResult`；
- **四阶并发重叠防卫**：提供 `DISCARD`（防雪崩跳过）、`QUEUE`（平滑排队）、`CONCURRENT`（并发并行）、`INTERRUPT`（打断前序）四大策略；
- **硬超时熔断与自适应重试**：配置 `timeout = "300ms"` 自动超时强中断，联动 `RetryUtil` 指数退避重试；
- **轻量集群互斥防死锁**：支持 `clusterLock = true` 与安全租约到期自动失效，抢锁失败安全跳过，防止多实例并发重复执行；
- **Spring 领域事件与告警桥接**：全流程发布 `JobStartingEvent`、`JobSuccessEvent`、`JobFailedEvent`、`JobTimeoutEvent`、`JobDiscardedEvent`，支持联动群机器人实时告警；
- **Actuator 运维管控端点**：提供 `/actuator/localjobs` 接口，支持全生命周期巡检、单任务详情与历史日志查询、手动触发、暂停与恢复。

---

## 2. 演示场景清单

| 演示场景 | 核心门面 / 注解 / SPI | 业务类与端点 | 核心特性与行为 |
| :--- | :--- | :--- | :--- |
| **Cron 定时对账任务** | `@LocalJob` | `SampleScheduledJobs.executeDailyReconciliation` | 演示标准 6 位 Cron 调度、`JobContext` 上下文日志与强类型 `JobResult` 产物返回 |
| **固定频率心跳采样** | `@LocalJob` | `SampleScheduledJobs.executeHeartbeatSampling` | 演示 `fixedRate` 固定频率与 `String` 返回值自适应包装 |
| **固定延迟清理任务** | `@LocalJob` | `SampleScheduledJobs.executeFixedDelayTask` | 演示 `fixedDelay` 上次结束到本次开始的间隔调度 |
| **并发丢弃防卫策略** | `OverlappingStrategy.DISCARD` | `SampleScheduledJobs.executeDiscardJob` | 演示前序任务未完成时，后序调度安全丢弃跳过，防止线程堆积雪崩 |
| **并发排队防卫策略** | `OverlappingStrategy.QUEUE` | `SampleScheduledJobs.executeQueueJob` | 演示前序任务未完成时，后序调度进入队列顺序等待执行 |
| **并发打断前序防卫** | `OverlappingStrategy.INTERRUPT` | `SampleScheduledJobs.executeInterruptJob` | 演示新调度到达时，立即向前序执行中任务发送线程中断信号 |
| **硬超时熔断与指数重试** | `timeout` + `maxRetries` | `SampleScheduledJobs.executeTimeoutRetryJob` | 演示单次执行硬超时强行中断，联动 `RetryUtil` 进行指数退避重试 |
| **轻量集群互斥与租约防死锁** | `clusterLock` + `clusterLockLeaseTime` | `SampleScheduledJobs.executeClusterLockJob` | 演示轻量集群互斥锁竞争，抢锁失败标记跳过，租约到期自动释放防死锁 |
| **编程式动态增删启停** | `LocalJobTemplate` | `POST /api/v1/jobs/dynamic-register` | 运行时编程式动态注册 Cron 任务、暂停调度、恢复调度与注销 |
| **Actuator 运维管控端点** | `LocalJobEndpoint` | `GET /actuator/localjobs` | 暴露任务清单、状态巡检、审计日志回查与 `POST` 立即触发/暂停/恢复 |
| **领域事件监听与告警桥接** | `JobAlertHandler` SPI | `SampleCustomJobAlertHandler` | 监听 `JobFailedEvent` 与 `JobTimeoutEvent` 并转发结构化告警上下文 |

---

## 3. 本地自验与自动化测试

### 3.1 执行场景端到端自动化测试（推荐）
```bash
mvn test -pl ateng-boot-job-local,ateng-boot-samples/ateng-boot-sample-job-local -am -T 1C -nsu -B
```

### 3.2 启动本地服务自验
```bash
mvn -pl ateng-boot-samples/ateng-boot-sample-job-local spring-boot:run
```

#### 1. 查询所有受管定时任务清单
```bash
curl -X GET "http://localhost:8080/api/v1/jobs"
```

#### 2. 手动立即触发指定任务
```bash
curl -X POST "http://localhost:8080/api/v1/jobs/sampleCronJob/trigger"
```

#### 3. 暂停定时任务
```bash
curl -X POST "http://localhost:8080/api/v1/jobs/sampleCronJob/pause"
```

#### 4. 恢复定时任务
```bash
curl -X POST "http://localhost:8080/api/v1/jobs/sampleCronJob/resume"
```

#### 5. 查看任务最近执行审计日志
```bash
curl -X GET "http://localhost:8080/api/v1/jobs/sampleCronJob/logs?limit=5"
```

#### 6. 编程式动态注册新任务
```bash
curl -X POST "http://localhost:8080/api/v1/jobs/dynamic-register" \
  -H "Content-Type: application/json" \
  -d '{
    "jobName": "dynamicHealthCheckJob",
    "cron": "0 0 * * * ?",
    "description": "动态注册的健康巡检任务"
  }'
```

#### 7. 通过 Actuator 端点巡检与管控
```bash
# 巡检所有任务状态
curl -X GET "http://localhost:8080/actuator/localjobs"

# 查询单任务详情与历史日志
curl -X GET "http://localhost:8080/actuator/localjobs/sampleCronJob"

# Actuator 手动触发执行任务
curl -X POST "http://localhost:8080/actuator/localjobs/sampleCronJob" \
  -H "Content-Type: application/json" \
  -d '{"action": "trigger"}'
```
