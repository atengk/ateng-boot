# ateng-boot-job-xxl 基础设施 Starter

> 面向生产级分布式环境的 XXL-Job 分布式任务调度增强基础设施 Starter，坚持纯原生、零臃肿依赖与显式强类型契约设计。提供方法级 `@AtengXxlJob` 生产增强注解、三阶平滑停机排空状态机（`registryRemove` -> 5s 排空轮询 -> Netty 销毁）、本地硬超时强中断看门狗（`LocalWatchdog`）、SLF4J 动态日志双写镜像（`XxlJobLogbackAppenderRegistrar`）、四维全链路上下文与租户透传（MDC `traceId` / 操作人 / 租户模式）、大数据游标流式与分片防 OOM 模板（`XxlShardingBatchTemplate` / `XxlMyBatisStreamingTemplate`）、官方 3.x OpenAPI 容灾门面（`XxlJobAdminTemplate`）、告警风暴抑制三板斧（首发告警 / 聚合摘要 / 故障自愈）、Spring Boot Actuator 运维管控端点（`/actuator/xxljobs`）、健康探针（`/actuator/health`）、Micrometer 监控度量（`ateng.job.xxl.execution`）及脱网极速调试门面（`XxlJobTestHelper`）。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.job.xxl.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **声明式增强** | 增强任务注解 | `@AtengXxlJob` | `enabled`<br>`context-aspect-enabled` | 声明式标注在 `@XxlJob` 方法上，开启硬超时、租户模式、参数校验与告警 | `XxlJobContextAspectTest` / `SimpleDemoJobHandler` |
| **声明式增强** | 任务标识名称 | `@AtengXxlJob.value` / `name` | - | `String value()` / `String name()`，JobHandler 唯一名称，支持别名对齐 | `XxlJobContextAspectTest` / `SimpleDemoJobHandler` |
| **声明式增强** | 本地硬超时阈值 | `@AtengXxlJob.timeout` | `watchdog.enabled`<br>`watchdog.default-timeout` | `String timeout()`，强类型超时字符串（如 `30s`、`5m`），看门狗超限强行中断 | `LocalWatchdogTest` / `ParamValidationJobHandler` |
| **声明式增强** | 自动参数校验开关 | `@AtengXxlJob.validateParam` | - | `boolean validateParam()`，默认 `true`，对 JobParam 执行 JSR-303 格式校验与 Fail-Fast | `XxlJobContextAspectTest` / `ParamValidationJobHandler` |
| **声明式增强** | 多租户隔离模式 | `@AtengXxlJob.tenantMode` | - | `TenantMode tenantMode()`，支持 `IGNORE`（全局忽略）、`PARAM`（参数绑定）、`NONE` | `XxlJobContextAspectTest` / `TenantJobHandler` |
| **声明式增强** | 租户参数键名 | `@AtengXxlJob.tenantParamKey` | - | `String tenantParamKey()`，当模式为 `PARAM` 时的 JSON 参数键名，默认 `tenantId` | `XxlJobContextAspectTest` / `TenantJobHandler` |
| **声明式增强** | 失败告警开关 | `@AtengXxlJob.alarmOnFailure` | `alert-enabled` | `boolean alarmOnFailure()`，是否在此任务失败时触发告警风暴抑制器，默认 `true` | `XxlJobContextAspectTest` / `AlertDemoJobHandler` |
| **声明式增强** | 监控指标开关 | `@AtengXxlJob.recordMetrics` | `metrics-enabled` | `boolean recordMetrics()`，是否上报 Micrometer 度量指标，默认 `true` | `XxlJobActuatorAndMetricsTest` / `SampleJobXxlIntegrationTest` |
| **执行器加固** | 生产增强执行器 | `AtengXxlJobSpringExecutor` | `admin-addresses`<br>`app-name`<br>`port` | 继承官方执行器，装配三阶停机排空、白名单防火墙与防重放签名 | `AtengXxlJobSpringExecutorTest` / `SampleJobXxlApplication` |
| **执行器加固** | 优雅停机状态机 | `AtengXxlJobSpringExecutor.destroy()` | `shutdown-await-termination-period` | 阶段一主动注销（`registryRemove`）-> 阶段二轮询排空 -> 阶段三物理销毁 | `AtengXxlJobSpringExecutorTest` |
| **安全防火墙** | CIDR IP 白名单 | `SecurityProperties.ipWhitelist` | `security.ip-whitelist` | 支持单个 IP 与 CIDR 掩码段（如 `192.168.1.0/24`），非法调度直接拦截拒绝 | `SecurityPropertiesTest` / `AtengEmbedServerTest` |
| **安全防火墙** | 防重放签名鉴权 | `SecurityProperties.signatureEnabled` | `security.signature-enabled`<br>`security.signature-window` | 基于调度时间戳与 AccessToken 进行 SHA-256 签名，防御网络重放攻击 | `SecurityPropertiesTest` / `AtengEmbedServerTest` |
| **分片流式批处理** | 步长流式顺序消费 | `XxlShardingBatchTemplate` | `sharding.default-batch-size` | `executeStep(initialId, batchSize, queryFunc, idExtractor, consumer)` 游标单线程拉取 | `XxlShardingBatchTemplateTest` |
| **分片流式批处理** | 步长逐条流式消费 | `XxlShardingBatchTemplate` | `sharding.default-batch-size` | `executeStepForEach(...)` 游标逐条拉取消费，彻底告别深分页与堆 OOM | `XxlShardingBatchTemplateTest` |
| **分片流式批处理** | 分片智能过滤流式消费 | `XxlShardingBatchTemplate` | `sharding.default-batch-size` | `executeShardedStep(sharding, initialId, batchSize, queryFunc, idExtractor, consumer)` | `XxlShardingBatchTemplateTest` / `ShardingBatchJobHandler` |
| **分片流式批处理** | 步长受管并发消费 | `XxlShardingBatchTemplate` | `sharding.default-max-concurrency` | `executeStepParallel(...)` 结合信号量背压并发分片拉取，透传四维上下文 | `XxlShardingBatchTemplateTest` |
| **分片流式批处理** | 分片广播受管并发消费 | `XxlShardingBatchTemplate` | `sharding.default-max-concurrency` | `executeShardedStepParallel(...)` 广播分片智能切片 + 受管线程池并发消费 | `XxlShardingBatchTemplateTest` |
| **分片流式批处理** | 执行结果统计模型 | `BatchExecutionResult` | - | 封装 `totalCount`、`successCount`、`failCount`、`batchCount`、`duration` | `XxlShardingBatchTemplateTest` / `ShardingBatchJobHandler` |
| **ORM 流式扩展** | MyBatis-Plus 游标批处理 | `XxlMyBatisStreamingTemplate` | - | `executeStreaming(...)` 联动 `StreamingQueryTemplate` 与 `Cursor` 顺序/并发流式拉取 | `XxlShardingBatchTemplateTest` |
| **OpenAPI 门面** | 新增任务调度 | `XxlJobAdminTemplate` | `admin-addresses`<br>`access-token` | `int addJob(XxlJobInfoParam param)`，封装官方 3.x OpenAPI 强类型接口 | `XxlJobAdminTemplateTest` / `SampleJobXxlIntegrationTest` |
| **OpenAPI 门面** | 修改任务调度 | `XxlJobAdminTemplate` | `admin-addresses`<br>`access-token` | `void updateJob(XxlJobInfoParam param)`，修改任务 Cron、执行器参数与路由策略 | `XxlJobAdminTemplateTest` |
| **OpenAPI 门面** | 启动任务调度 | `XxlJobAdminTemplate` | `admin-addresses`<br>`access-token` | `void startJob(int id)`，将任务状态推进为 RUNNING 周期调度 | `XxlJobAdminTemplateTest` |
| **OpenAPI 门面** | 停止任务调度 | `XxlJobAdminTemplate` | `admin-addresses`<br>`access-token` | `void stopJob(int id)`，将任务状态变更为 STOP 停止周期调度 | `XxlJobAdminTemplateTest` |
| **OpenAPI 门面** | 移除任务调度 | `XxlJobAdminTemplate` | `admin-addresses`<br>`access-token` | `void removeJob(int id)`，从调度中心物理删除任务定义 | `XxlJobAdminTemplateTest` |
| **OpenAPI 门面** | 即时手动触发 | `XxlJobAdminTemplate` | `admin-addresses`<br>`access-token` | `void triggerJob(int id, String executorParam)`，向调度中心发起一次即时调度 | `XxlJobAdminTemplateTest` / `SampleJobXxlIntegrationTest` |
| **脱网极速调试** | 无参脱网手动触发 | `XxlJobTestHelper` | - | `XxlJobExecutionResult trigger(String handlerName)`，脱网本地内省触发 JobHandler | `XxlJobActuatorAndMetricsTest` / `SampleJobXxlIntegrationTest` |
| **脱网极速调试** | 带参脱网手动触发 | `XxlJobTestHelper` | - | `XxlJobExecutionResult trigger(String handlerName, String param)`，装配参数快速测试 | `XxlJobActuatorAndMetricsTest` / `SampleJobXxlIntegrationTest` |
| **脱网极速调试** | 分片脱网手动触发 | `XxlJobTestHelper` | - | `XxlJobExecutionResult trigger(String handlerName, String param, int shardIndex, int shardTotal)` | `XxlJobActuatorAndMetricsTest` / `SampleJobXxlIntegrationTest` |
| **运维端点** | 全量 Handler 状态清单 | `XxlJobEndpoint` | `actuator-enabled` | `GET /actuator/xxljobs`，列出已注册的 JobHandler 列表、方法签名与增强注解属性 | `XxlJobActuatorAndMetricsTest` / `SampleJobXxlIntegrationTest` |
| **运维端点** | 单 Handler 详情与诊断 | `XxlJobEndpoint` | `actuator-enabled` | `GET /actuator/xxljobs/{handler}`，获取指定 JobHandler 的元数据与增强配置 | `XxlJobActuatorAndMetricsTest` / `SampleJobXxlIntegrationTest` |
| **运维端点** | 动态脱网运维触发 | `XxlJobEndpoint` | `actuator-enabled` | `POST /actuator/xxljobs/{handler}`，请求体传入 param 与分片参数，在线手动触发 | `XxlJobActuatorAndMetricsTest` / `SampleJobXxlIntegrationTest` |
| **健康探针** | 执行器健康状态检测 | `XxlJobHealthIndicator` | `actuator-enabled` | 暴露于 `/actuator/health`，报告 `UP` / `DOWN` / `OUT_OF_SERVICE` 与调度中心状态 | `XxlJobActuatorAndMetricsTest` / `SampleJobXxlIntegrationTest` |
| **监控度量** | Micrometer 核心指标 | `XxlJobMetricsBinder` | `metrics-enabled` | 发布 `ateng.job.xxl.execution` 指标（Throughput、Failures、P50/P95/P99 分位数耗时） | `XxlJobActuatorAndMetricsTest` / `SampleJobXxlIntegrationTest` |
| **告警自愈** | 告警风暴抑制器 | `XxlJobAlertAggregator` | `alert.enabled`<br>`alert.silence-period`<br>`alert.failure-threshold` | 聚合失败异常，首发告警 -> 静默期聚合富文本摘要卡片 -> 成功时自愈恢复通知 | `XxlJobAlertAggregatorTest` / `SampleJobXxlIntegrationTest` |
| **告警自愈** | 告警领域事件 | `XxlJobAlertEvent` | - | Spring 领域事件，携带 `alertType`、`jobHandler`、`param`、`traceId`、`throwable` | `XxlJobAlertAggregatorTest` / `SampleJobXxlIntegrationTest` |
| **看门狗机制** | 本地硬超时守护线程 | `LocalWatchdog` | `watchdog.enabled`<br>`watchdog.check-interval` | 定时轮询活跃任务，超限向执行线程调用 `interrupt()` 并记录告警 | `LocalWatchdogTest` |
| **日志镜像** | SLF4J 双写动态桥接 | `XxlJobLogbackAppenderRegistrar` | `log-mirror-enabled` | 自动向 Logback Root Logger 挂载动态 Appender，在调度线程将日志镜像至调度中心 | `XxlJobLogbackAppenderRegistrarTest` / `SimpleDemoJobHandler` |
| **自动装配** | 模块自动装配入口 | `AtengXxlJobAutoConfiguration` | `enabled` | Spring Boot 3 标准自动装配，平滑探测调度中心配置，标配完整缺省 Bean 守卫 | `AtengXxlJobAutoConfigurationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-job-xxl` 是专为分布式生产级微服务与定时调度集群打造的高可靠、高韧性执行器基础设施 Starter：
- **纯原生零臃肿底座**：100% 依托 Spring Boot 3 与 JDK 17 原生能力，零引入第三方全量工具包；
- **三阶平滑停机排空状态机**：服务停机时先向调度中心发起 `registryRemove` 主动注销停止派发新任务，随后进行 5s 轮询等待活跃任务排空，最后销毁 Netty 物理句柄，杜绝停机杀任务引发的数据脏状态；
- **双重主动安全防护**：原生内置 CIDR IP 白名单安全防火墙与防重放时间戳签名校验，彻底防御内网非法渗透与未授权伪造调度；
- **四维上下文全链路透传**：每次调度自动生成 32 位无锁 MDC `traceId`，无缝继承租户模式（`IGNORE` / `PARAM`）与操作人信息，并在异步子任务与分片消费终态安全清理现场；
- **大数据流式游标防 OOM 模板**：提供 `XxlShardingBatchTemplate`，基于主键步长流式分批与背压并发消费，规避深度分页与堆内存膨胀；具备 MyBatis-Plus 游标扩展类 `XxlMyBatisStreamingTemplate`；
- **告警风暴抑制与故障自愈**：失败首发告警、静默期内自动收敛为富文本聚合摘要卡片、故障修复后自动发送绿色自愈通知，并支持领域事件与群机器人双通道分发；
- **云原生运维度量与脱网极速调试**：内置 `/actuator/xxljobs` 运维端点、`/actuator/health` 健康探针、Micrometer 百分位监控指标与 `XxlJobTestHelper` 本地脱网即时触发门面。

### Maven 坐标引入

在已导入 `ateng-boot-bom` 的工程中，无需声明版本号即可直接引入：

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-job-xxl</artifactId>
</dependency>
```

---

## 2. 最小起步体验 (Happy Path & Out-of-the-Box)

以下代码与配置 100% 逆向提取自已在 CI 与本地全量全绿跑通的场景样例工程 [`ateng-boot-sample-job-xxl`](../ateng-boot-samples/ateng-boot-sample-job-xxl)。

### 步骤一：配置应用属性 (`application.yml`)

```yaml
server:
  port: 8080

ateng:
  boot:
    job:
      xxl:
        enabled: true
        admin-addresses: http://127.0.0.1:8080/xxl-job-admin
        app-name: xxl-job-executor-sample
        ip: ""                  # 留空时自动推导本机 IP
        port: 9999              # 执行器 Netty 通信端口
        access-token: default_token
        log-path: ./logs/xxl-job
        log-retention-days: 7
        context-aspect-enabled: true
        alert-enabled: true
        actuator-enabled: true
        metrics-enabled: true
        log-mirror-enabled: true
        shutdown-await-termination-period: 30s
        security:
          signature-enabled: false
          ip-whitelist: 127.0.0.1, 192.168.0.0/16
        watchdog:
          enabled: true
          check-interval: 500ms
          default-timeout: 60s
        alert:
          enabled: true
          silence-period: 5m
          failure-threshold: 3
          channel: ops-alert
        sharding:
          default-batch-size: 1000
          default-max-concurrency: 8

management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, xxljobs
  endpoint:
    health:
      show-details: always
```

### 步骤二：编写声明式任务 Handler

```java
package io.github.atengk.boot.sample.job.xxl.handler;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class SimpleDemoJobHandler {

    private static final Logger log = LoggerFactory.getLogger(SimpleDemoJobHandler.class);

    @XxlJob("simpleGreetingJob")
    @AtengXxlJob(value = "simpleGreetingJob", timeout = "10s")
    public void executeSimpleGreeting() {
        String jobParam = XxlJobHelper.getJobParam();
        String traceId = MDC.get("traceId");

        // 业务日志：通过 XxlJobLogbackAppenderRegistrar 自动实时镜像推送到调度中心
        log.info(">>>>>> [SimpleDemoJobHandler] 执行定时问候任务, 入参: {}, traceId: {}", jobParam, traceId);

        XxlJobHelper.handleSuccess("问候任务执行成功 [traceId=" + traceId + "]");
    }
}
```

---

## 3. 生产扩展接管 (Customization & Extensibility)

### 3.1 JSR-303 强类型参数校验与 Fail-Fast

在分布式调度中，调度中心传入的 JSON 参数往往缺乏格式约束。通过挂载强类型 DTO 并在 `@AtengXxlJob` 开启校验，可在执行业务前瞬间拦截格式错误，记录结构化错误日志并触发首发告警：

```java
// 1. 声明强类型参数 DTO
public record UserSyncParam(
        @NotBlank(message = "同步业务类型不能为空")
        String syncType,

        @NotNull(message = "单批同步上限不能为空")
        @Min(value = 1, message = "单批同步上限至少为 1")
        @Max(value = 1000, message = "单批同步上限不能超过 1000")
        Integer batchLimit
) {}

// 2. 挂载到 JobHandler
@Component
public class ParamValidationJobHandler {

    @XxlJob("userSyncJob")
    @AtengXxlJob(value = "userSyncJob", timeout = "30s", validateParam = true)
    public void executeUserSync(UserSyncParam param) {
        // 自动完成 JSON 反序列化并执行 JSR-303 校验；校验失败自动 Fail-Fast 阻断
        log.info(">>>>>> [ParamValidationJobHandler] 开始同步用户数据: syncType={}, limit={}",
                param.syncType(), param.batchLimit());
        XxlJobHelper.handleSuccess("用户数据同步完成");
    }
}
```

### 3.2 大数据分片广播与流式防 OOM 模板 (`XxlShardingBatchTemplate`)

针对千万级大表与分布式广播分片场景，框架提供 `XxlShardingBatchTemplate`，基于主键步长流式切片消费，并自动完成四维上下文与 XXL-Job 日志句柄的子线程透传与清理：

```java
@Component
public class ShardingBatchJobHandler {

    private final XxlShardingBatchTemplate batchTemplate;

    public ShardingBatchJobHandler(XxlShardingBatchTemplate batchTemplate) {
        this.batchTemplate = batchTemplate;
    }

    @XxlJob("shardingBatchJob")
    @AtengXxlJob(value = "shardingBatchJob", timeout = "30s")
    public void executeShardingBatch(ShardingContext shardingContext) {
        AtomicInteger processedCount = new AtomicInteger();

        // 联动 ShardingContext 分片智能匹配 + 步长游标流式拉取
        BatchExecutionResult summary = batchTemplate.executeShardedStep(
                shardingContext,
                0L,
                100,
                (lastId, limit) -> orderMapper.selectNextBatch(lastId, limit),
                Order::getId,
                batch -> {
                    for (Order order : batch) {
                        processOrder(order);
                        processedCount.incrementAndGet();
                    }
                }
        );

        XxlJobHelper.log("分片批处理执行完成: 成功处理 {} 条, 失败 {} 条, 总耗时 {}ms",
                summary.getSuccessCount(), summary.getFailCount(), summary.getDuration().toMillis());
        XxlJobHelper.handleSuccess(String.format("分片 %d 处理完毕, 累计消费 %d 条数据",
                shardingContext.getShardIndex(), summary.getSuccessCount()));
    }
}
```

### 3.3 监听告警领域事件并桥接自定义通知

当任务失败、达到静默聚合阈值或自愈恢复时，框架会通过 Spring 事件总线广播 `XxlJobAlertEvent`，业务可按需实现监听器或扩展群机器人转发：

```java
@Component
public class CustomAlertEventListener {

    @EventListener
    public void onJobAlert(XxlJobAlertEvent event) {
        switch (event.getAlertType()) {
            case FIRST_FAILURE -> log.error("🚨 任务 [{}] 发生首发故障! traceId={}, error={}",
                    event.getJobHandler(), event.getTraceId(), event.getErrorMessage());
            case SUMMARY -> log.warn("📊 任务 [{}] 处于告警静默期, 累计失败 {} 次, 总耗时 {}ms",
                    event.getJobHandler(), event.getFailureCount(), event.getDurationMs());
            case RECOVERY -> log.info("💚 任务 [{}] 故障自愈恢复! 耗时 {}ms",
                    event.getJobHandler(), event.getDurationMs());
        }
    }
}
```

### 3.4 脱网极速调试门面 (`XxlJobTestHelper`)

在无外部调度中心的离线单测或本地调试场景下，开发者可直接使用 `XxlJobTestHelper` 触发任务，框架自动模拟调度上下文与链路追踪，无需启动 Admin 服务：

```java
@Autowired
private XxlJobTestHelper testHelper;

@Test
void testOfflineExecution() {
    // 1. 无参手动触发
    XxlJobExecutionResult result = testHelper.trigger("simpleGreetingJob");
    assertThat(result.isSuccess()).isTrue();

    // 2. 带参手动触发（自动解包底层异常，返回真实业务异常）
    XxlJobExecutionResult paramResult = testHelper.trigger("userSyncJob", "{\"syncType\":\"FULL\",\"batchLimit\":500}");
    assertThat(paramResult.getCode()).isEqualTo(200);

    // 3. 分片广播模拟触发 (当前分片 0，总分片 2)
    XxlJobExecutionResult shardResult = testHelper.trigger("shardingBatchJob", null, 0, 2);
    assertThat(shardResult.isSuccess()).isTrue();
}
```

---

## 4. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 4.1 三阶平滑停机排空状态机 (Graceful Shutdown)
- **阶段一（主动注销）**：向调度中心集群发送 `registryRemove` 注销请求，调度中心感知节点摘除，立即停止派发新触发任务；
- **阶段二（任务排空）**：执行器检查内部 `activeJobCount` 计数器，若存在运行中任务，按照配置的 `shutdown-await-termination-period`（默认 30s）进行平滑轮询等待，确保当前调度事务顺利完成；
- **阶段三（物理销毁）**：优雅停止 Netty 服务端 `AtengEmbedServer`，安全释放端口绑定并终止守护线程池，防止出现线程挂起与端口悬挂占用。

### 4.2 CIDR IP 白名单安全防火墙与防重放签名
- **网络层防御**：`AtengEmbedServer` 在 Netty Channel 入站时比对对端 SocketAddress，支持单 IP 与 CIDR 子网段；非白名单 IP 的连接在握手阶段直接断开；
- **防重放签名**：开启 `security.signature-enabled` 时，请求必须携带 `XXL-JOB-ACCESS-TOKEN`、`XXL-JOB-TIMESTAMP` 与 `XXL-JOB-SIGNATURE`；若时间戳超出容忍窗口（默认 5 分钟）或 HMAC-SHA256 签名不符，立即返回 403 阻断重放攻击。

### 4.3 本地硬超时中断看门狗 (`LocalWatchdog`)
- 官方 XXL-Job 超时判定依赖调度中心调度轮询，容易因网络抖动造成执行器端任务无限死锁挂起；
- 本 Starter 引入本地 `LocalWatchdog`，以毫秒级精度维护活跃任务时间轮；一旦单次任务执行超过 `@AtengXxlJob.timeout` 阈值，守护线程立即对目标任务线程调用 `interrupt()` 触发强中断，并记录 `LocalWatchdog` 超时告警。

### 4.4 告警风暴抑制三板斧与状态自愈
- **首发告警 (FIRST_FAILURE)**：当某个 JobHandler 从正常变为失败时，立即投递首发告警，确保值班人员第一时间获知故障；
- **静默期聚合 (SUMMARY)**：在 `alert.silence-period`（默认 5 分钟）内，抑制重复发送报警刷屏；在静默期满或达到失败阈值时，自动生成包含累计失败次数、总耗时与最近一次异常堆栈的富文本聚合卡片；
- **故障自愈恢复 (RECOVERY)**：当任务恢复正常并单次执行成功时，自动发出绿色自愈卡片，清除内部故障状态机计数，保障运维体验闭环。

### 4.5 Actuator 监控端点与 Micrometer 度量
- **Actuator 运维端点**：
  - `GET /actuator/xxljobs`：获取所有受管 `@XxlJob` 列表与其增强元数据；
  - `GET /actuator/xxljobs/{handler}`：获取指定 Handler 的配置与执行统计；
  - `POST /actuator/xxljobs/{handler}`：在线脱网运维手动触发一次执行；
- **健康探针 (`/actuator/health`)**：
  - 探针 `XxlJobHealthIndicator` 汇报当前执行器运行状态、调度中心地址与 Netty 通信端口；
- **Micrometer 指标**：
  - 发布 `ateng.job.xxl.execution` Timer 度量，携带 `handler`、`status`（success / failure）、`tenant` 标签，全真上报吞吐量与 P50/P95/P99 分位数耗时。
