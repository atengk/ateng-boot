# ateng-boot-sample-job-snail 分布式调度与协同重试双擎自验工程

> 专注于分布式任务调度与协同重试双擎基础设施（SnailJob 2.0.2 官方核心增强、`SnailJobTestHelper` 100% 离线脱网单测沙箱、`SnailShardingBatchTemplate` 大数据流式游标切片、`TenantJobTemplate` 多租户隔离遍历、`SnailRetryTemplate` 分布式协同重试与手动上报、四维上下文快照穿透、三阶平滑停机排空与告警防风暴聚合）。

---

## 1. 场景痛点与功能定位

在现代化微服务与分布式架构中，分布式调度与异常重试是保障数据最终一致性与系统高可用的两座基石：
- **分布式调度与广播分片**：千万级数据处理时单机难以承载，需要分布式任务分片与大数据步长流式拉取，防止 JVM 堆内存 OOM 与数据库深分页慢查询；
- **协同重试与死信沉降**：异步交易与支付对账失败时，单机重试在停机发布时极易丢失现场，需要支持跨时间、跨节点的分布式重试与死信兜底回调；
- **四维上下文跨节点丢失**：异步任务与重试执行时丢失 MDC `traceId`、多租户上下文与操作人信息，导致审计困难与越权风险；
- **停机发布任务被截断**：发布上线时在途调度被强杀引发半事务脏数据，亟需优雅的三阶平滑停机排空机制；
- **脱网单测难**：缺乏 SnailJob 服务端时单测通常无法运行或容易污染外部环境。

`ateng-boot-sample-job-snail` 作为 1:1 对齐 `ateng-boot-job-snail` 的场景样例工程，提供真实端到端可运行沙箱：
- **100% 离线脱网单测沙箱**：基于 `SnailJobTestHelper` 内存反射驱动，在脱机与 CI 环境下秒级自洽测试所有调度与重试方法；
- **大数据流式分片批处理**：通过 `SnailShardingBatchTemplate` 结合 `ShardingContext` 智能匹配分片，实现主键游标流式拉取与背压保护；
- **多租户全模式支持**：提供 `IGNORE`（全局）、`PARAM`（单租户）与 `ITERATE`（租户遍历沙箱）三模支持；
- **声明式与编程式协同重试**：提供 `@Retryable`、`@AtengSnailRetry` 与 `SnailRetryTemplate` 对称门面，支持手动失败上报；
- **运维管控与告警防风暴**：集成 `/actuator/snailjobs`、`/actuator/health`、Micrometer 指标度量及三阶告警聚合状态机。

---

## 2. 演示场景清单

| 演示场景 | 核心门面 / 注解 / SPI | 业务类与端点 | 核心特性与行为 |
| :--- | :--- | :--- | :--- |
| **基础极简问候任务** | `@JobExecutor` | `SimpleDemoJobHandler.simpleGreetingJob` | 演示分布式调度任务声明与 MDC `traceId` 注入 |
| **超时守卫增强任务** | `@AtengSnailJob(timeout = "500ms")` | `SimpleDemoJobHandler.timeoutGuardedJob` | 演示本地看门狗超时中断与增强注解集成 |
| **强类型入参校验** | `@JobExecutor` + JSR-303 | `ParamValidationJobHandler.userSyncJob` | 演示 `UserSyncParam` 强类型校验与 Fail-Fast 异常拦截 |
| **广播分片流式批处理** | `SnailShardingBatchTemplate` | `ShardingBatchJobHandler.shardingBatchJob` | 演示 `ShardingContext` 广播分片过滤与大数据主键步长流式消费 |
| **多租户隔离调度** | `TenantJobTemplate` / `@AtengSnailJob` | `TenantJobHandler` | 演示全局忽略、参数绑定与租户遍历沙箱三模执行 |
| **分布式协同重试** | `@Retryable` / `SnailRetryTemplate` | `RetryDemoService.payOrder` | 演示声明式注解重试、编程式闭包与手动失败上报 |
| **告警防风暴聚合** | `SnailJobAlertAggregator` | `AlertDemoJobHandler.alertDemoJob` | 演示首发告警、静默窗口聚合摘要与自愈恢复三阶状态机 |
| **运维监控与健康探针** | `SnailJobEndpoint` / `SnailJobHealthIndicator` | `GET /actuator/snailjobs`, `GET /actuator/health` | 暴露调度与重试元数据、在途排空相态与健康探针 |
| **Web 统一契约接口** | `Result<T>` | `SampleSnailJobController` | 演示 Controller 传输层统一强类型响应契约 |

---

## 3. 本地自验与自动化测试

### 3.1 执行场景端到端自动化测试（离线默认模式）
```bash
mvn test -pl ateng-boot-job-snail,ateng-boot-samples/ateng-boot-sample-job-snail -am -T 1C -nsu -B
```

### 3.2 远程服务真实联调模式（可选）
若本地拥有真实 SnailJob 调度服务端（默认端口 `17888`）：
1. 复制 `application-local.example.yml` 为 `application-local.yml`：
   ```bash
   cp src/main/resources/application-local.example.yml src/main/resources/application-local.yml
   ```
2. 在 `application-local.yml` 中填写真实服务端地址与访问凭据；
3. 激活 `local` profile 执行集成联调测试：
   ```bash
   mvn test -pl ateng-boot-job-snail,ateng-boot-samples/ateng-boot-sample-job-snail -Dspring.profiles.active=local -am -T 1C -nsu -B
   ```
