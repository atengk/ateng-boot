# ateng-boot-job-xxl 基础设施 Starter

> 面向生产级分布式环境的 XXL-Job 分布式任务调度增强基础设施 Starter，坚持纯原生、零臃肿依赖与显式强类型契约设计。提供方法级 `@AtengXxlJob` 生产增强注解、三阶平滑停机排空状态机（`registryRemove` -> 轮询排空 -> Netty 销毁）、本地硬超时强中断看门狗（`LocalWatchdog`）、SLF4J 动态日志双写镜像（`XxlJobLogbackAppenderRegistrar`）、四维全链路上下文与租户透传（MDC `traceId` / 操作人 / 租户模式）、大数据游标流式与分片防 OOM 模板（`XxlShardingBatchTemplate` / `XxlMyBatisStreamingTemplate`）、官方 3.x OpenAPI 容灾门面（`XxlJobAdminTemplate`）、多租户隔离遍历模板（`TenantJobTemplate`）、告警风暴抑制三阶状态机（首发告警 / 聚合摘要 / 故障自愈）、Spring Boot Actuator 运维管控端点（`/actuator/xxljobs`）、健康探针（`/actuator/health`）、Micrometer 监控度量（`ateng.job.xxl.execution`）及脱网极速调试门面（`XxlJobTestHelper`）。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.job.xxl.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **声明式增强** | 增强任务注解 | `@AtengXxlJob` | `enabled`<br>`context-aspect-enabled` | 声明式标注在 `@XxlJob` 方法上，按需织入四维上下文、本地硬超时与租户模式 | `XxlJobContextAspectTest` / `SimpleDemoJobHandler` |
| **声明式增强** | 任务标识名称 | `@AtengXxlJob.value` | - | `String value()`，指定 JobHandler 标识名称（默认留空，优先以 `@XxlJob.value()` 为准） | `XxlJobContextAspectTest` / `SimpleDemoJobHandler` |
| **声明式增强** | 本地硬超时阈值 | `@AtengXxlJob.timeout` | `default-job-timeout` | `String timeout()`，声明式本地硬超时字符串（如 `30s`、`5m`），看门狗超限强行中断工作线程 | `LocalWatchdogTest` / `SimpleDemoJobHandler` |
| **声明式增强** | 多租户隔离模式 | `@AtengXxlJob.tenantMode` | - | `JobTenantMode tenantMode()`，支持 `IGNORE`（忽略租户）、`PARAM`（入参绑定）、`ITERATE`（全租户遍历） | `XxlJobContextAspectTest` / `TenantJobHandler` |
| **参数防卫校验** | JSR-303 快速失败 | 方法形参声明 `@Valid` | - | 切面自动将调度 JSON 入参反序列化为形参强类型 DTO 并执行 JSR-303 防卫与 Fail-Fast | `XxlJobContextAspectTest` / `ParamValidationJobHandler` |
| **执行器加固** | 生产增强执行器 | `AtengXxlJobSpringExecutor` | `admin-addresses`<br>`appname`<br>`port`<br>`ip`<br>`address` | 继承官方执行器，装配三阶停机排空、CIDR 防火墙与端口冲突顺延自愈 | `AtengXxlJobSpringExecutorTest` / `SampleJobXxlApplication` |
| **执行器加固** | 优雅停机状态机 | `AtengXxlJobSpringExecutor.stop()` | `drain-timeout` | 阶段一主动注销（`registryRemove`）-> 阶段二轮询排空 -> 阶段三物理销毁 Netty | `AtengXxlJobSpringExecutorTest` |
| **安全防火墙** | 入站 CIDR 白名单 | `XxlJobFirewallInboundHandler` | `cidr-whitelist` | 支持单个 IP 与 CIDR 掩码段（如 `192.168.0.0/16`），非白名单入站连接直接阻断 | `XxlJobFirewallTest` / `AtengEmbedServerTest` |
| **安全防火墙** | 令牌防空鉴权 | `XxlJobFirewallInboundHandler` | `access-token`<br>`require-access-token` | 校验调度请求头中的 AccessToken，支持启动期未配置令牌时 Fail-Fast 快速拦截 | `XxlJobFirewallTest` / `AtengXxlJobAutoConfigurationTest` |
| **分片流式批处理** | 步长流式顺序消费 | `XxlShardingBatchTemplate` | - | `executeStep(initialId, batchSize, queryFunc, idExtractor, consumer)` 游标单线程拉取 | `XxlShardingBatchTemplateTest` |
| **分片流式批处理** | 步长逐条流式消费 | `XxlShardingBatchTemplate` | - | `executeStepForEach(...)` 游标逐条拉取消费，彻底告别深分页与堆 OOM | `XxlShardingBatchTemplateTest` |
| **分片流式批处理** | 分片智能过滤流式消费 | `XxlShardingBatchTemplate` | - | `executeShardedStep(sharding, initialId, batchSize, queryFunc, idExtractor, consumer)` | `XxlShardingBatchTemplateTest` / `ShardingBatchJobHandler` |
| **分片流式批处理** | 步长受管并发消费 | `XxlShardingBatchTemplate` | - | `executeStepParallel(...)` 结合信号量背压并发分片拉取，透传四维上下文 | `XxlShardingBatchTemplateTest` |
| **分片流式批处理** | 分片广播受管并发消费 | `XxlShardingBatchTemplate` | - | `executeShardedStepParallel(...)` 广播分片智能切片 + 受管线程池并发消费 | `XxlShardingBatchTemplateTest` |
| **分片流式批处理** | 执行结果统计模型 | `BatchExecutionResult` | - | 封装 `totalCount`、`successCount`、`failCount`、`batchCount`、`duration` | `XxlShardingBatchTemplateTest` / `ShardingBatchJobHandler` |
| **ORM 流式扩展** | MyBatis-Plus 游标批处理 | `XxlMyBatisStreamingTemplate` | - | `executeStreaming(...)` 联动 `StreamingQueryTemplate` 与 `Cursor` 顺序/并发流式拉取 | `XxlShardingBatchTemplateTest` |
| **OpenAPI 门面** | 调度中心生命周期管理 | `XxlJobAdminTemplate` | `admin-addresses`<br>`access-token` | `addJob`、`updateJob`、`startJob`、`stopJob`、`removeJob`、`triggerJob`，封装官方 3.x OpenAPI 强类型接口，支持多节点故障转移 (注: 官方未开放按 ID 详情查询 SPI) | `XxlJobAdminTemplateTest` / `SampleJobXxlIntegrationTest` |
| **脱网极速调试** | 脱网单测手动触发 | `XxlJobTestHelper` | - | `XxlJobTestResult trigger(handler, param, shardIndex, shardTotal, tenantId)`，脱网本地内省触发 JobHandler | `XxlJobActuatorAndMetricsTest` / `SampleJobXxlIntegrationTest` |
| **运维端点** | 全量 Handler 状态清单 | `XxlJobEndpoint` | `enabled` | `GET /actuator/xxljobs`，列出已注册的 JobHandler 概览、执行器端口与元数据 | `XxlJobActuatorAndMetricsTest` / `SampleJobXxlIntegrationTest` |
| **运维端点** | 单 Handler 详情与诊断 | `XxlJobEndpoint` | `enabled` | `GET /actuator/xxljobs/{handler}`，查询指定 Handler 的反射元数据、目标类与方法签名 | `XxlJobActuatorAndMetricsTest` / `SampleJobXxlIntegrationTest` |
| **运维端点** | 动态脱网运维触发 | `XxlJobEndpoint` | `enabled` | `POST /actuator/xxljobs/{handler}`，请求体传入 param 与分片参数，在线手动触发 | `XxlJobActuatorAndMetricsTest` / `SampleJobXxlIntegrationTest` |
| **健康探针** | 执行器健康状态检测 | `XxlJobHealthIndicator` | `enabled` | 暴露于 `/actuator/health`，优先读取执行器运行时顺延自愈端口与 IP，上报网络连通性 | `XxlJobActuatorAndMetricsTest` / `SampleJobXxlIntegrationTest` |
| **监控度量** | Micrometer 核心指标 | `XxlJobMetricsBinder` | - | 发布 `ateng.job.xxl.execution` Timer 指标（Throughput、Failures、P50/P95/P99 分位数耗时） | `XxlJobActuatorAndMetricsTest` / `SampleJobXxlIntegrationTest` |
| **告警自愈** | 告警风暴抑制器 | `XxlJobAlertAggregator` | `alert-enabled`<br>`alert-suppress-duration`<br>`alert-suppress-threshold` | 聚合失败异常，首发立即告警 -> 抑制窗口内静默聚合为富文本摘要 -> 成功时自愈恢复 | `XxlJobAlertAggregatorTest` / `SampleJobXxlIntegrationTest` |
| **告警自愈** | 告警领域事件 | `XxlJobAlertEvent` | - | Spring 标准领域事件，携带 `alertType` (`INITIAL_FAILURE` / `FAILURE_SUMMARY` / `RECOVERY`)、`failureCount`、`throwable` | `XxlJobAlertAggregatorTest` / `SampleJobXxlIntegrationTest` |
| **看门狗机制** | 本地硬超时守护时间轮 | `LocalWatchdog` | `default-job-timeout` | 基于 Netty `HashedWheelTimer` 与受管线程工厂，超限强行中断工作线程并广播 `JobTimeoutEvent` | `LocalWatchdogTest` |
| **多租户遍历** | 多租户隔离遍历执行模板 | `TenantJobTemplate` | - | `executeForEachTenant(action)`，在独立沙箱上下文中流式遍历各租户执行，杜绝数据串写 | `TenantJobTemplateTest` |
| **多租户遍历** | 租户列表提供者 SPI | `TenantListProvider` | - | `List<String> getTenantIds()`，供业务系统注册有效租户提供者，配合模板遍历 | `TenantJobTemplateTest` |
| **上下文传播** | 调度上下文异步全链路传播 | `XxlJobContextSnapshotProvider` | - | 实现 `AsyncContextSnapshotProvider` SPI，治理 `InheritableThreadLocal` 跨池浅拷贝污染 | `XxlJobContextSnapshotProviderTest` |
| **日志镜像** | SLF4J 双写动态桥接 | `XxlJobLogbackAppenderRegistrar` | `log-mirror-enabled` | 自动向 Logback Root Logger 挂载动态 Appender，在调度线程将日志镜像至调度中心 | `XxlJobLogbackAppenderRegistrarTest` / `SimpleDemoJobHandler` |
| **自动装配** | 模块自动装配入口 | `AtengXxlJobAutoConfiguration` | `enabled` | Spring Boot 3 标准自动装配，平滑探测调度中心配置，标配完整缺省 Bean 守卫 | `AtengXxlJobAutoConfigurationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-job-xxl` 是专为分布式生产级微服务与定时调度集群打造的高可靠、高韧性执行器基础设施 Starter：
- **纯原生零臃肿底座**：100% 依托 Spring Boot 3 与 JDK 17 原生能力，零引入第三方全量工具包；
- **三阶平滑停机排空状态机**：服务停机时先向调度中心发起 `registryRemove` 主动注销停止派发新任务，随后根据 `drain-timeout` 轮询等待活跃任务排空，最后销毁 Netty 物理句柄，杜绝停机杀任务引发的数据脏状态；
- **入站 CIDR 防火墙与令牌防空**：原生内置 CIDR IP 白名单安全防火墙与 AccessToken 快速失败防卫，彻底防御内网非法渗透与未授权伪造调度；
- **四维上下文全链路透传**：每次调度自动生成 32 位无锁 MDC `traceId`，无缝继承租户模式（`IGNORE` / `PARAM` / `ITERATE`）与操作人信息，并在异步子任务与分片消费终态安全清理现场；
- **大数据流式游标防 OOM 模板**：提供 `XxlShardingBatchTemplate`，基于主键步长流式分批与背压并发消费，规避深度分页与堆内存膨胀；具备 MyBatis-Plus 游标扩展类 `XxlMyBatisStreamingTemplate`；
- **多租户隔离遍历模板**：提供 `TenantJobTemplate`，结合 `TenantListProvider` SPI，在独立租户沙箱中流式安全执行；
- **告警风暴抑制与故障自愈**：失败首发告警、静默期内自动收敛为富文本聚合摘要卡片、故障修复后自动发送绿色自愈通知，并支持领域事件与协同机器人双通道分发；
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
        enabled: true                                      # 是否启用执行器自动装配
        admin-addresses: http://127.0.0.1:8080/xxl-job-admin # 调度中心核心服务地址
        appname: xxl-job-sample-executor                   # 执行器应用标识
        access-token: default_token                        # 通信访问令牌
        port: 9999                                         # 执行器 Netty 通信端口 (支持冲突顺延自愈)
        log-path: ./logs/xxl-job/jobhandler                # 日志存储根目录
        log-retention-days: 7                              # 日志清理保留天数
        timeout: 30s                                       # RPC 通信超时阈值
        managed-pool-enabled: true                         # 是否将调度方法提交至 Core 受管异步线程池
        alert-enabled: true                                # 是否启用告警防风暴聚合
        alert-suppress-duration: 5m                        # 告警风暴抑制周期时限
        alert-suppress-threshold: 3                        # 触发告警摘要的连续失败次数阈值
        drain-timeout: 30s                                 # 平滑停机最大排空等待时长
        default-job-timeout: 1h                            # 默认看门狗硬超时阈值 (防无界挂起)

management:
  endpoints:
    web:
      exposure:
        include: "health,info,metrics,xxljobs"
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

在分布式调度中，调度中心传入的 JSON 参数往往缺乏格式约束。开发者只需在方法形参上直接声明经过 JSR-303 注解标注的 DTO，框架切面将自动从 `JobParam` 反序列化并执行无状态格式校验；校验不通过直接快速失败中断，记录结构化错误日志并触发首发告警：

```java
// 1. 声明强类型参数 DTO
public class UserSyncParam implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "同步批次号 batchNo 不能为空")
    private String batchNo;

    @NotNull(message = "批大小 batchSize 不能为空")
    @Min(value = 1, message = "批大小不能小于 1")
    @Max(value = 1000, message = "单批同步上限不能超过 1000")
    private Integer batchSize;

    private String tenantId;
    // Getters and Setters...
}

// 2. 在 JobHandler 方法形参中声明 @Valid
@Component
public class ParamValidationJobHandler {

    private static final Logger log = LoggerFactory.getLogger(ParamValidationJobHandler.class);

    @XxlJob("userSyncJob")
    @AtengXxlJob(value = "userSyncJob")
    public void executeUserSync(@Valid UserSyncParam param) {
        log.info(">>>>>> [ParamValidationJobHandler] 开始执行用户同步任务, 批次: {}, 批大小: {}, 租户: {}",
                param.getBatchNo(), param.getBatchSize(), param.getTenantId());

        XxlJobHelper.log("用户同步批次 [{}] 校验通过，正在处理 {} 条数据...", param.getBatchNo(), param.getBatchSize());
        XxlJobHelper.handleSuccess("同步任务成功完成, 处理批大小: " + param.getBatchSize());
    }
}
```

### 3.2 大数据分片广播与流式防 OOM 模板 (`XxlShardingBatchTemplate`)

针对千万级大表与分布式广播分片场景，框架提供 `XxlShardingBatchTemplate`，在方法形参注入 `ShardingContext`，基于主键步长流式切片消费，并自动完成四维上下文与 XXL-Job 日志句柄的子线程透传与清理：

```java
@Component
public class ShardingBatchJobHandler {

    private static final Logger log = LoggerFactory.getLogger(ShardingBatchJobHandler.class);
    private final XxlShardingBatchTemplate batchTemplate;

    public ShardingBatchJobHandler(XxlShardingBatchTemplate batchTemplate) {
        this.batchTemplate = batchTemplate;
    }

    @XxlJob("shardingBatchJob")
    @AtengXxlJob(value = "shardingBatchJob", timeout = "30s")
    public void executeShardingBatch(ShardingContext shardingContext) {
        log.info(">>>>>> [ShardingBatchJobHandler] 开始执行分片任务: 当前节点分片={}/{}",
                shardingContext.getShardIndex(), shardingContext.getShardTotal());

        AtomicInteger processedCount = new AtomicInteger();

        // 联动 ShardingContext 分片智能匹配 + 步长游标流式拉取
        BatchExecutionResult summary = batchTemplate.executeShardedStep(
                shardingContext,
                0L,
                100,
                (lastId, limit) -> orderMapper.selectNextBatch(lastId, limit),
                MockOrder::id,
                batch -> {
                    for (MockOrder order : batch) {
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

    private static final Logger log = LoggerFactory.getLogger(CustomAlertEventListener.class);

    @EventListener
    public void onJobAlert(XxlJobAlertEvent event) {
        switch (event.getAlertType()) {
            case INITIAL_FAILURE -> log.error("🚨 任务 [{}] 发生首发故障! traceId={}, error={}",
                    event.getJobHandler(), event.getTraceId(),
                    event.getThrowable() != null ? event.getThrowable().getMessage() : "unknown");
            case FAILURE_SUMMARY -> log.warn("📊 任务 [{}] 处于告警静默期, 累计失败 {} 次, 故障总历时 {} 秒",
                    event.getJobHandler(), event.getFailureCount(), event.getFailureDuration().toSeconds());
            case RECOVERY -> log.info("💚 任务 [{}] 故障自愈恢复! 期间曾累计失败 {} 次",
                    event.getJobHandler(), event.getFailureCount());
        }
    }
}
```

### 3.4 脱网极速调试门面 (`XxlJobTestHelper`)

在无外部调度中心的离线单测或本地调试场景下，开发者可直接使用 `XxlJobTestHelper` 静态门面触发任务，框架自动模拟调度上下文与链路追踪，无需启动 Admin 服务：

```java
@Test
void testOfflineExecution() {
    // 1. 无参手动触发
    XxlJobTestResult result = XxlJobTestHelper.trigger("simpleGreetingJob");
    assertTrue(result.isSuccess());
    assertEquals(200, result.handleCode());

    // 2. 带参手动触发（自动解包底层异常，返回真实业务异常）
    XxlJobTestResult paramResult = XxlJobTestHelper.trigger("userSyncJob", "{\"batchNo\":\"BATCH-001\",\"batchSize\":500}");
    assertEquals(200, paramResult.handleCode());

    // 3. 分片广播模拟触发 (当前分片 0，总分片 2)
    XxlJobTestResult shardResult = XxlJobTestHelper.trigger("shardingBatchJob", null, 0, 2);
    assertTrue(shardResult.isSuccess());
}
```

---

## 4. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 4.1 三阶平滑停机排空状态机 (Graceful Shutdown)
- **阶段一（主动注销）**：向调度中心集群发送 `registryRemove` 注销请求，调度中心感知节点摘除，立即停止派发新触发任务；
- **阶段二（任务排空）**：执行器在配置的 `drain-timeout`（默认 30s）内进行平滑轮询等待活跃任务执行完成；
- **阶段三（物理销毁）**：优雅停止 Netty 服务端 `AtengEmbedServer`，终止常驻工作线程与辅助线程，安全释放端口绑定，杜绝线程挂起与端口悬挂占用。

### 4.2 CIDR IP 白名单安全防火墙与 AccessToken 快速防卫
- **网络层防御**：`AtengEmbedServer` 在 Netty Channel 入站时比对对端 SocketAddress，支持单 IP 与 CIDR 子网段（配置项 `cidr-whitelist`）；非白名单 IP 的连接在握手阶段直接断开；
- **令牌防空防卫**：执行器入站严格校验 `XXL-JOB-ACCESS-TOKEN`；可配置 `require-access-token: true` 在启动期实施 Fail-Fast 强制令牌保护。

### 4.3 本地硬超时中断看门狗 (`LocalWatchdog`)
- 官方 XXL-Job 超时判定依赖调度中心轮询，容易因网络抖动造成执行器端任务无限死锁挂起；
- 本 Starter 引入本地 `LocalWatchdog`，基于 Netty `HashedWheelTimer` 与 Spring 受管线程工厂维护活跃任务时间轮；一旦单次任务执行超过 `@AtengXxlJob.timeout`（或全局 `default-job-timeout`），守护线程立即对目标任务线程调用 `interrupt()` 触发强中断，并广播 `JobTimeoutEvent`。

### 4.4 告警风暴抑制三阶状态机与状态自愈
- **首发告警 (`INITIAL_FAILURE`)**：当任务首次执行失败时，立即投递首发告警，确保值班人员第一时间获知故障；
- **静默期聚合 (`FAILURE_SUMMARY`)**：在 `alert-suppress-duration`（默认 5 分钟）与 `alert-suppress-threshold`（默认 10 次）双重窗口内抑制单次重复骚扰；在窗口期满或步长溢出时，触发包含累计失败总数与最新异常的聚合卡片，随后重置阶段步长重新进入静默；
- **故障自愈恢复 (`RECOVERY`)**：当任务在经历故障后首次执行成功时，自动发出绿色自愈卡片，汇报期间累计失败总数与历时，清除故障计数，闭环自愈状态机。

### 4.5 Actuator 监控端点与 Micrometer 度量
- **Actuator 运维端点**：
  - `GET /actuator/xxljobs`：获取所有受管 `@XxlJob` 列表与其增强元数据；
  - `GET /actuator/xxljobs/{handler}`：查询指定 Handler 的反射元数据、目标 Bean 与方法签名；
  - `POST /actuator/xxljobs/{handler}`：在线脱网运维手动触发一次执行；
- **健康探针 (`/actuator/health`)**：
  - 探针 `XxlJobHealthIndicator` 动态探测执行器运行时顺延自愈端口与 IP，上报网络监听连通性；
- **Micrometer 指标**：
  - 发布 `ateng.job.xxl.execution` Timer 度量，携带 `handler`、`status`（success / failure）、`tenant` 标签，全真上报吞吐量与耗时分布。
