# ateng-boot-job-snail 基础设施 Starter

> 面向企业级分布式微服务环境的 SnailJob 调度与协同重试双擎生产加固基础设施 Starter，坚持纯原生、零臃肿依赖与显式强类型契约设计。提供方法级 `@AtengSnailJob` 调度生产增强注解、`@AtengSnailRetry` 协同重试增强注解、K8s 停机三阶平滑排空状态机（`GracefulDrainStateMachine`）、本地硬超时强中断看门狗（`SnailLocalWatchdog`）、SLF4J 动态日志双写镜像（`SnailJobLogbackAppender`）、四维全链路上下文透传与远程重试保真还原（MDC `traceId` / 操作人 / 租户模式 / 调度上下文）、大数据游标流式与广播分片防 OOM 模板（`SnailShardingBatchTemplate` / `SnailMyBatisStreamingTemplate`）、官方 OpenAPI 编程式管理门面（`SnailJobAdminTemplate`）、多租户隔离遍历模板（`TenantJobTemplate`）、编程式分布式重试门面（`SnailRetryTemplate`）、告警风暴抑制三阶状态机（首发告警 / 聚合摘要 / 故障自愈）、死信领域事件直通通道（`SnailRetryDeadLetterEvent`）、Spring Boot Actuator 运维管控端点（`/actuator/snailjobs`）、健康探针（`/actuator/health`）、Micrometer 监控度量（`ateng.job.snail.execution` / `ateng.job.snail.retry.execution`）及脱网极速调试门面（`SnailJobTestHelper`）。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.job.snail.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **声明式调度** | 调度增强注解 | `@AtengSnailJob` | `enabled`<br>`job.enabled`<br>`job.context-aspect-enabled` | 声明式标注于 `@JobExecutor` 方法上，按需织入四维上下文、本地硬看门狗与租户隔离模式 | `SnailJobContextAspectTest` / `SimpleDemoJobHandler` |
| **声明式调度** | 任务标识名称 | `@AtengSnailJob.value` | - | `String value()`，指定任务标识名称（优先以原生 `@JobExecutor.name()` 为准） | `SnailJobContextAspectTest` / `SimpleDemoJobHandler` |
| **声明式调度** | 本地硬超时阈值 | `@AtengSnailJob.timeout` | `default-job-timeout` | `String timeout()`，声明式本地硬超时字符串（如 `500ms`、`10s`），看门狗超限强行中断工作线程 | `SnailLocalWatchdogTest` / `SimpleDemoJobHandler` |
| **声明式调度** | 租户隔离策略 | `@AtengSnailJob.tenantMode` | `job.tenant-template-enabled` | `JobTenantMode tenantMode()`，支持 `NONE`、`IGNORE`（平台忽略）、`PARAM`（入参绑定）、`ITERATE`（租户遍历） | `SnailJobContextAspectTest` / `TenantJobHandler` |
| **声明式调度** | 告警开关声明 | `@AtengSnailJob.alert` | `alert.enabled` | `boolean alert()`，是否在任务执行异常时接入三阶告警防风暴聚合状态机 | `SnailJobAlertAggregatorTest` / `AlertDemoJobHandler` |
| **声明式重试** | 协同重试注解 | `@AtengSnailRetry` | `enabled`<br>`retry.enabled`<br>`retry.context-aspect-enabled` | 声明式标注于 `@Retryable` 方法上，封装四维现场快照封包、跨节点还原与死信通道 | `SnailRetryContextAspectTest` / `RetryDemoService` |
| **声明式重试** | 业务重试场景 | `@AtengSnailRetry.scene` | - | `String scene()`，指定重试业务场景唯一标识（优先以原生 `@Retryable.scene()` 为准） | `SnailRetryContextAspectTest` / `RetryDemoService` |
| **声明式重试** | SpEL 幂等键表达式 | `@AtengSnailRetry.idempotentSpel` | - | `String idempotentSpel()`，支持 SpEL 动态解析业务方法入参生成分布式唯一幂等键 | `IdempotentIdGeneratorTest` / `RetryDemoService` |
| **声明式重试** | 业务单号表达式 | `@AtengSnailRetry.bizNoSpel` | - | `String bizNoSpel()`，提取业务单据编号（如订单号），便于在服务端运维后台检索追踪 | `IdempotentIdGeneratorTest` / `RetryDemoService` |
| **声明式重试** | 死信告警开关 | `@AtengSnailRetry.deadLetterAlert` | `alert.enabled` | `boolean deadLetterAlert()`，重试用尽触发死信时是否广播告警领域事件 | `SnailRetryDeadLetterCallbackTest` / `RetryDemoService` |
| **声明式重试** | 上下文快照封包 | `@AtengSnailRetry.captureContext` | - | `boolean captureContext()`，是否自动将当前 MDC、多租户及操作人封装进重试现场 | `SnailRetryContextAspectTest` / `RetryDemoService` |
| **参数防卫校验** | JSR-303 快速失败 | 方法形参声明 `@Valid` | - | 切面自动将调度 JSON 入参反序列化为形参强类型 DTO 并执行 JSR-303 防卫与 Fail-Fast | `SnailJobContextAspectTest` / `ParamValidationJobHandler` |
| **编程式重试** | 编程式重试执行 | `SnailRetryTemplate` | `retry.enabled` | `execute(scene, targetClassName, params, action)` 自动管理四维现场与重试闭包 | `SnailRetryTemplateTest` / `RetryDemoService` |
| **编程式重试** | 幂等编程式重试 | `SnailRetryTemplate` | `retry.enabled` | `execute(scene, targetClassName, params, idempotentId, bizNo, action)` 携带显式幂等键执行 | `SnailRetryTemplateTest` / `RetryDemoService` |
| **编程式重试** | 对称手动上报 | `SnailRetryTemplate` | `retry.enabled` | `reportManualRetry(scene, targetClassName, params, idempotentId, bizNo)` 外部调用失败主动上报 | `SnailRetryTemplateTest` / `RetryDemoService` |
| **分片流式批处理** | 步长流式顺序消费 | `SnailShardingBatchTemplate` | `job.batch-template-enabled` | `executeStep(initialId, batchSize, queryFunc, idExtractor, consumer)` 游标单线程拉取 | `SnailShardingBatchTemplateTest` |
| **分片流式批处理** | 步长逐条流式消费 | `SnailShardingBatchTemplate` | `job.batch-template-enabled` | `executeStepForEach(...)` 游标逐条拉取消费，彻底告别深分页与堆 OOM | `SnailShardingBatchTemplateTest` |
| **分片流式批处理** | 分片智能过滤流式消费 | `SnailShardingBatchTemplate` | `job.batch-template-enabled` | `executeShardedStep(sharding, initialId, batchSize, queryFunc, idExtractor, consumer)` | `SnailShardingBatchTemplateTest` / `ShardingBatchJobHandler` |
| **分片流式批处理** | 步长受管并发消费 | `SnailShardingBatchTemplate` | `job.batch-template-enabled` | `executeStepParallel(...)` 结合信号量背压并发分片拉取，透传四维上下文 | `SnailShardingBatchTemplateTest` |
| **分片流式批处理** | 分片广播受管并发消费 | `SnailShardingBatchTemplate` | `job.batch-template-enabled` | `executeShardedStepParallel(...)` 广播分片智能切片 + 受管线程池并发消费 | `SnailShardingBatchTemplateTest` / `ShardingBatchJobHandler` |
| **分片流式批处理** | 执行结果统计模型 | `BatchExecutionResult` | - | 封装 `totalCount`、`successCount`、`failCount`、`batchCount`、`duration` | `SnailShardingBatchTemplateTest` / `ShardingBatchJobHandler` |
| **ORM 流式扩展** | MyBatis-Plus 游标批处理 | `SnailMyBatisStreamingTemplate` | `job.batch-template-enabled` | `executeStreaming(...)` 联动 `StreamingQueryTemplate` 与 `Cursor` 顺序/并发流式拉取 | `SnailShardingBatchTemplateTest` |
| **OpenAPI 门面** | 任务管理全生命周期 | `SnailJobAdminTemplate` | `openapi.enabled`<br>`openapi.port`<br>`openapi.prefix` | `getJobDetail`、`saveJob`、`updateJob`、`deleteJobs`、`triggerJob`、`updateJobStatus` | `SnailJobAdminTemplateTest` |
| **OpenAPI 门面** | 工作流 DAG 编排管理 | `SnailJobAdminTemplate` | `openapi.enabled`<br>`openapi.port`<br>`openapi.prefix` | `getWorkflowDetail`、`saveWorkflow`、`updateWorkflow`、`deleteWorkflows`、`triggerWorkflow` | `SnailJobAdminTemplateTest` |
| **OpenAPI 门面** | 死信重试与治理 | `SnailJobAdminTemplate` | `openapi.enabled`<br>`openapi.port`<br>`openapi.prefix` | `retryDeadLetter`、`rollbackDeadLetter`、`deleteDeadLetters` 运维重试与状态回滚 | `SnailJobAdminTemplateTest` |
| **多租户遍历** | 多租户隔离遍历执行模板 | `TenantJobTemplate` | `job.tenant-template-enabled` | `executeForEachTenant(action)`，在独立沙箱上下文中流式遍历各租户执行，杜绝数据串写 | `TenantJobTemplateTest` / `TenantJobHandler` |
| **多租户遍历** | 租户列表提供者 SPI | `TenantListProvider` | - | `List<String> getTenantIds()`，供业务系统注册有效租户提供者，配合模板遍历 | `TenantJobTemplateTest` |
| **脱网极速调试** | 脱网单测调度触发 | `SnailJobTestHelper` | - | `triggerJob(jobName, param, shardIndex, shardTotal, tenantId)` 脱网本地内省触发 Job | `SnailJobTestHelperTest` / `SampleJobSnailIntegrationTest` |
| **脱网极速调试** | 脱网单测重试触发 | `SnailJobTestHelper` | - | `triggerRetry(scene, args)`、`triggerRetryWithExecutor(scene, executor, args)` 驱动重试 | `SnailJobTestHelperTest` / `SampleJobSnailIntegrationTest` |
| **运维端点** | 全量任务与重试清单 | `SnailJobEndpoint` | `enabled` | `GET /actuator/snailjobs`，列出已注册的 Job/Retry 概览、在途任务与排空状态 | `SnailJobEndpointTest` / `SampleJobSnailIntegrationTest` |
| **运维端点** | 单任务详情与反射元数据 | `SnailJobEndpoint` | `enabled` | `GET /actuator/snailjobs/{jobName}`，查询指定任务的反射元数据、目标类与方法签名 | `SnailJobEndpointTest` |
| **运维端点** | 在线手动运维触发 | `SnailJobEndpoint` | `enabled` | `POST /actuator/snailjobs/{jobName}`，请求体传入 param 与分片参数，在线手动触发 | `SnailJobEndpointTest` |
| **健康探针** | 执行器双擎健康检测 | `SnailJobHealthIndicator` | `enabled` | 暴露于 `/actuator/health`，动态探测 SnailJob 服务端通信活性、gRPC 通道与停机态 | `SnailJobHealthIndicatorTest` / `SampleJobSnailIntegrationTest` |
| **监控度量** | Micrometer 核心指标 | `SnailJobMetricsBinder` | - | 发布 `ateng.job.snail.execution` 与 `ateng.job.snail.retry.execution` Timer 指标 | `SnailJobMetricsBinderTest` |
| **告警自愈** | 告警风暴抑制器 | `SnailJobAlertAggregator` | `alert.enabled`<br>`alert.suppress-duration`<br>`alert.suppress-threshold` | 聚合失败异常，首发立即告警 -> 抑制窗口内静默聚合为摘要 -> 成功时自愈恢复 | `SnailJobAlertAggregatorTest` / `AlertDemoJobHandler` |
| **告警自愈** | 调度告警领域事件 | `SnailJobAlertEvent` | - | Spring 标准领域事件，携带 `alertType` (`INITIAL_FAILURE`/`FAILURE_SUMMARY`/`RECOVERY`) | `SnailJobAlertAggregatorTest` / `SampleJobSnailIntegrationTest` |
| **死信治理** | 重试死信领域事件 | `SnailRetryDeadLetterEvent` | - | Spring 标准领域事件，携带 `scene`、`bizNo`、`idempotentId`、`retries`、`throwable` | `SnailRetryDeadLetterCallbackTest` / `SampleJobSnailIntegrationTest` |
| **死信治理** | 重试完成死信回调 SPI | `SnailRetryDeadLetterCallback` | - | 实现 SnailJob `RetryCompleteCallback`，在重试最大次数终态自动桥接死信告警卡片 | `SnailRetryDeadLetterCallbackTest` |
| **看门狗机制** | 本地硬超时守护时间轮 | `SnailLocalWatchdog` | `default-job-timeout` | 基于 Netty `HashedWheelTimer` 与受管线程工厂，超限强行中断工作线程并广播事件 | `SnailLocalWatchdogTest` |
| **停机排空** | 三阶平滑排空状态机 | `GracefulDrainStateMachine` | `drain-timeout` | 阶段一标记排空 -> 阶段二轮询在途排空 -> 阶段三物理断开 gRPC 通道，防御数据脏写 | `GracefulDrainStateMachineTest` |
| **上下文传播** | 调度上下文异步全链路传播 | `SnailJobContextSnapshotProvider` | - | 实现 `AsyncContextSnapshotProvider` SPI，治理跨受管线程池异步透传与清理现场 | `SnailJobContextSnapshotProviderTest` |
| **日志镜像** | SLF4J 动态双写镜像 | `SnailJobLogbackAppender` | `log-mirror-enabled` | 动态向 Logback Root Logger 注入 Appender，在调度与重试期间将日志镜像至服务端 | `SnailJobLogbackAppenderTest` / `SimpleDemoJobHandler` |
| **自动装配** | 模块自动装配入口 | `AtengSnailJobAutoConfiguration` | `enabled` | Spring Boot 3 标准自动装配，平滑探测服务端配置，标配完整缺省 Bean 守卫 | `AtengSnailJobAutoConfigurationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-job-snail` 是专为分布式生产级微服务、复杂工作流与分布式异常重试集群打造的双擎高韧性基础设施 Starter：
- **纯原生零臃肿底座**：100% 依托 Spring Boot 3 与 JDK 17 原生能力，零引入第三方全量工具包；
- **三阶平滑停机排空状态机**：服务停机时先激活排空标记阻断新任务派发，随后根据 `drain-timeout` 轮询等待活跃调度与重试在途排空，最后销毁 gRPC 物理通道句柄，杜绝杀进程引发的数据脏状态；
- **四维上下文全链路透传与保真还原**：调度与重试执行期间自动生成 32 位无锁 MDC `traceId`，无缝继承租户模式（`IGNORE` / `PARAM` / `ITERATE`）与操作人信息；在分布式远程重试跨时间、跨节点重放时保真还原当时快照，任务终态严格清理现场；
- **大数据流式游标防 OOM 模板**：提供 `SnailShardingBatchTemplate`，基于主键步长流式分批与背压并发消费，规避深度分页与堆内存膨胀；具备 MyBatis-Plus 游标扩展类 `SnailMyBatisStreamingTemplate`；
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
server:
  port: 8080

spring:
  application:
    name: ateng-boot-sample-job-snail

ateng:
  boot:
    web:
      trace:
        enabled: true
    job:
      snail:
        enabled: true                                      # 是否启用 SnailJob 双擎自动装配
        server: "127.0.0.1:17888"                          # SnailJob 服务端通信地址
        group: "ateng-boot-sample-job-snail"               # 执行器组标识
        namespace: "default"                               # 命名空间
        token: "default_token"                             # 通信访问令牌
        port: 17889                                        # 本地 RPC 通信端口
        timeout: 10s                                       # 通信超时阈值
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
          suppress-duration: 5m                            # 告警静默抑制窗口周期
          suppress-threshold: 3                            # 触发告警摘要的连续失败次数阈值

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,snailjobs
  endpoint:
    health:
      show-details: always
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
    @AtengSnailJob(value = "timeoutGuardedJob", timeout = "500ms")
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
import io.github.atengk.boot.job.snail.annotation.AtengSnailRetry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RetryDemoService {

    /**
     * 声明式协同重试业务方法：通过 SpEL 动态生成幂等键与业务单号，绑定四维现场快照
     */
    @Retryable(scene = "orderPayRetry", retryStrategy = com.aizuda.snailjob.client.core.strategy.RetryStrategy.LOCAL)
    @AtengSnailRetry(scene = "orderPayRetry", idempotentSpel = "#orderId", bizNoSpel = "#orderId")
    public String payOrder(String orderId, Double amount) {
        log.info("[RetryDemoService] 开始执行支付业务: orderId={}, amount={}", orderId, amount);

        if ("FAIL_ORDER".equals(orderId)) {
            throw new IllegalStateException("模拟支付网关网络超时 [orderId=" + orderId + "]");
        }

        return "PAY_SUCCESS:" + orderId;
    }
}
```

---

## 3. 生产扩展接管 (Customization & Extensibility)

### 3.1 JSR-303 强类型参数校验与 Fail-Fast

在分布式调度中，控制台下发的 JSON 参数往往缺乏契约约束。开发者只需在方法形参上直接声明经过 JSR-303 注解标注的 DTO，框架切面将自动从 `JobArgs` 反序列化并执行无状态格式校验；校验不通过直接快速失败中断，记录结构化错误日志并触发首发告警：

```java
// 1. 声明强类型参数 DTO
@Data
public class UserSyncParam implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "同步批次号 batchNo 不能为空")
    private String batchNo;

    @NotNull(message = "批大小 batchSize 不能为空")
    @Min(value = 1, message = "批大小必须大于 0")
    private Integer batchSize;

    private String departmentId;
}

// 2. 在 JobHandler 方法形参中声明 @Valid
@Slf4j
@Component
public class ParamValidationJobHandler {

    @AtengSnailJob(value = "userSyncJob")
    @JobExecutor(name = "userSyncJob")
    public ExecuteResult userSyncJob(@Valid UserSyncParam param) {
        log.info("[ParamValidationJobHandler] 参数校验通过, 执行同步: 批次={}, 数量={}",
                param.getBatchNo(), param.getBatchSize());

        return ExecuteResult.success("Sync completed for batch " + param.getBatchNo());
    }
}
```

### 3.2 大数据分片广播与流式防 OOM 模板 (`SnailShardingBatchTemplate`)

针对千万级大表与分布式广播分片场景，框架提供 `SnailShardingBatchTemplate`，在方法形参注入 `ShardingContext`，基于主键步长流式切片消费，并自动完成四维上下文与日志镜像句柄的子线程透传与清理：

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ShardingBatchJobHandler {

    private final SnailShardingBatchTemplate shardingBatchTemplate;

    @JobExecutor(name = "shardingBatchJob")
    public ExecuteResult shardingBatchJob(JobArgs args) {
        ShardingContext sharding = ShardingContext.current();
        log.info("[ShardingBatchJobHandler] 开始执行分布式分片批处理, 当前分片: {}", sharding);

        AtomicInteger processedItems = new AtomicInteger(0);

        // 联动 ShardingContext 分片智能匹配 + 步长游标流式拉取消费
        BatchExecutionResult result = shardingBatchTemplate.executeShardedStep(
                sharding,
                0L,
                10,
                (lastId, limit) -> orderMapper.selectNextBatch(lastId, limit),
                Order::getId,
                matchedBatch -> {
                    log.info("[ShardingBatchJobHandler] 分片 [{}] 匹配消费切片批次: 数量={}",
                            sharding != null ? sharding.getShardIndex() : "ALL", matchedBatch.size());
                    processedItems.addAndGet(matchedBatch.size());
                }
        );

        log.info("[ShardingBatchJobHandler] 分布式分片批处理完成: 总扫描={}, 实际消费={}, 耗时={}ms",
                result.totalCount(), processedItems.get(), result.duration().toMillis());

        return ExecuteResult.success("Sharding batch completed, scanned=" + result.totalCount() + ", processed=" + processedItems.get());
    }
}
```

### 3.3 编程式分布式重试与对称手动上报 (`SnailRetryTemplate`)

针对无法通过 `@Retryable` 注解声明的动态链路（如动态重试策略、外部 HTTP 适配器、条件降级），直接注入 `SnailRetryTemplate` 编程式门面：

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class RetryDemoService {

    private final SnailRetryTemplate snailRetryTemplate;

    /**
     * 编程式闭包重试
     */
    public String executeWithProgrammaticRetry(String bizId) {
        return snailRetryTemplate.execute(
                "programmaticScene",
                RetryDemoService.class.getName(),
                new Object[]{bizId},
                () -> {
                    log.info("[RetryDemoService] 编程式重试执行: bizId={}", bizId);
                    return "SUCCESS:" + bizId;
                }
        );
    }

    /**
     * 对称手动上报重试 (外部失败主动推送至 SnailJob 服务端纳管)
     */
    public boolean reportExternalFailure(String orderId) {
        return snailRetryTemplate.reportManualRetry(
                "externalSyncScene",
                RetryDemoService.class.getName(),
                new Object[]{orderId},
                "IDEM-" + orderId,
                orderId
        );
    }
}
```

### 3.4 多租户隔离调度任务三模实测 (`TenantJobTemplate`)

框架提供对多租户隔离场景的标准化抽象，支持三种隔离模式：

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantJobHandler {

    private final TenantJobTemplate tenantJobTemplate;

    // 模式一：全局忽略多租户 (平台级归档)
    @AtengSnailJob(value = "globalArchiveJob", tenantMode = JobTenantMode.IGNORE)
    @JobExecutor(name = "globalArchiveJob")
    public ExecuteResult globalArchiveJob() {
        boolean ignoreTenant = TenantContextHolder.isIgnoreTenant();
        log.info("[TenantJobHandler] 执行全局归档任务, 忽略租户标记={}", ignoreTenant);
        return ExecuteResult.success("Global archive finished, ignoreTenant=" + ignoreTenant);
    }

    // 模式二：单租户限定任务 (从入参或上下文绑定当前租户)
    @AtengSnailJob(value = "tenantScopedJob", tenantMode = JobTenantMode.PARAM)
    @JobExecutor(name = "tenantScopedJob")
    public ExecuteResult tenantScopedJob(JobArgs args) {
        String currentTenant = TenantContextHolder.getTenantId();
        log.info("[TenantJobHandler] 执行租户专属任务, 租户ID: {}", currentTenant);
        return ExecuteResult.success("Tenant job finished for tenant=" + currentTenant);
    }

    // 模式三：租户批量遍历执行 (按租户沙箱隔离逐一遍历执行)
    @AtengSnailJob(value = "tenantBatchJob", tenantMode = JobTenantMode.ITERATE)
    @JobExecutor(name = "tenantBatchJob")
    public ExecuteResult tenantBatchJob() {
        List<String> tenants = List.of("tenant-alpha", "tenant-beta");
        AtomicInteger count = new AtomicInteger(0);

        tenantJobTemplate.executeForEachTenant(tenants, tenantId -> {
            log.info("[TenantJobHandler] 正在沙箱执行租户 [{}] 的结算逻辑, 当前上下文租户={}",
                    tenantId, TenantContextHolder.getTenantId());
            count.incrementAndGet();
        });

        return ExecuteResult.success("Tenant batch finished: total=" + tenants.size() + ", success=" + count.get());
    }
}
```

### 3.5 监听告警领域事件并桥接自定义通知

当任务失败、达到静默聚合阈值或自愈恢复时，框架会通过 Spring 事件总线广播 `SnailJobAlertEvent`；当重试耗尽触发死信时，广播 `SnailRetryDeadLetterEvent`：

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

### 3.6 脱网极速调试门面 (`SnailJobTestHelper`)

在无外部 SnailJob 服务端的离线单测或本地调试场景下，开发者可直接注入 `SnailJobTestHelper` 门面触发任务，框架自动模拟调度上下文与链路追踪，无需启动独立服务端：

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

        // 3. 分片广播模拟触发 (当前分片 0，总分片 2)
        SnailJobTestResult shardResult = testHelper.triggerJob("shardingBatchJob", null, 0, 2);
        assertThat(shardResult.isSuccess()).isTrue();

        // 4. 重试场景模拟触发
        SnailRetryTestResult retryResult = testHelper.triggerRetry("orderPayRetry", "ORD-1001", 88.0);
        assertThat(retryResult.isSuccess()).isTrue();
    }
}
```

---

## 4. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 4.1 三阶平滑停机排空状态机 (`GracefulDrainStateMachine`)
- **阶段一（标记排空）**：服务准备停机时，状态机将 `draining` 置为 `true`，拒绝并阻断后续新派发任务进入；
- **阶段二（在途排空）**：执行器在配置的 `drain-timeout`（默认 30s）内进行平滑轮询等待活跃调度任务（`inFlightJobs`）与在途重试任务（`inFlightRetries`）执行完成；
- **阶段三（物理销毁）**：优雅销毁底层 Netty/gRPC 物理通道连接，释放长连接句柄与端口，杜绝强杀进程引发的数据脏状态。

### 4.2 本地硬超时中断看门狗 (`SnailLocalWatchdog`)
- 官方调度超时判定依赖服务端轮询，容易因网络抖动造成客户端任务无限挂起耗尽线程；
- 本 Starter 引入本地 `SnailLocalWatchdog`，基于 Netty `HashedWheelTimer` 与 Spring 受管线程工厂维护活跃任务时间轮；一旦单次任务执行超过 `@AtengSnailJob.timeout`（或全局 `default-job-timeout`），守护线程立即对目标任务线程调用 `interrupt()` 触发强中断，并广播告警事件。

### 4.3 远程重试保真还原与四维快照封包
- 分布式重试跨时间、跨节点重新触发时，原生重试框架会丢失当时的会话上下文；
- 切面在本地执行失败时自动封包当前四维上下文（MDC `traceId`、多租户、操作人与 SnailJob 调度上下文）至重试参数快照中；在远程重放时保真还原当时的上下文环境，并在执行完成时严格清理现场，防止线程复用污染。

### 4.4 告警风暴抑制三阶状态机与状态自愈
- **首发告警 (`INITIAL_FAILURE`)**：当任务首次执行失败时，立即投递首发告警，确保值班人员第一时间获知故障；
- **静默期聚合 (`FAILURE_SUMMARY`)**：在 `alert.suppress-duration`（默认 5 分钟）与 `alert.suppress-threshold`（默认 3 次）双重窗口内抑制单次重复骚扰；在窗口期满或步长溢出时，触发包含累计失败总数与最新异常的聚合卡片，随后重置阶段步长重新进入静默；
- **故障自愈恢复 (`RECOVERY`)**：当任务在经历故障后首次执行成功时，自动发出绿色自愈卡片，汇报期间累计失败总数与历时，清除故障计数，闭环自愈状态机。

### 4.5 Actuator 监控端点与 Micrometer 度量
- **Actuator 运维端点**：
  - `GET /actuator/snailjobs`：获取所有受管 `@JobExecutor` 与 `@Retryable` 列表、在途任务数与排空状态；
  - `GET /actuator/snailjobs/{jobName}`：查询指定任务的反射元数据、目标 Bean 与方法签名；
  - `POST /actuator/snailjobs/{jobName}`：在线脱网运维手动触发一次执行；
- **健康探针 (`/actuator/health`)**：
  - 探针 `SnailJobHealthIndicator` 动态探测执行器与 SnailJob 服务端的 gRPC 长连接与网络可达性；
- **Micrometer 指标**：
  - 发布 `ateng.job.snail.execution` 与 `ateng.job.snail.retry.execution` Timer 度量，携带 `jobName`/`scene`、`status`（success / failure）、`tenant` 标签，全真上报吞吐量与耗时分布。
