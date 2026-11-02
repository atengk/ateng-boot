# ateng-boot-job-xxl 基础设施 Starter

> 面向生产级分布式环境的 XXL-Job 分布式任务调度增强基础设施 Starter，坚持纯原生、零臃肿依赖与显式强类型契约设计。提供方法级 `@AtengXxlJob` 生产增强注解、三阶平滑停机排空状态机（`registryRemove` -> 轮询排空 -> Netty 销毁）、本地硬超时强中断看门狗与两级降级补偿机制（同 Bean 就地补偿 + `JobTimeoutFallbackHandler` 全局 SPI）、任务执行切面责任链拦截器 SPI（`XxlJobInterceptor` 优雅拦截跳过防告警风暴）、节点级任务运行时治理总线（`JobRuntimeGovernor` 内存动态静默与恢复）、SLF4J 动态日志双写镜像（`XxlJobLogbackAppenderRegistrar`）、四维全链路上下文与租户透传（MDC `traceId` / 操作人 / 租户模式）、大数据游标流式分片防 OOM 模板（`XxlShardingBatchTemplate`，含 `FailedBatch<T>` 失败切片收集与进度打卡）、多租户隔离与受管并发遍历模板（`TenantJobTemplate.executeForEachTenantParallel` 信号量背压并发）、官方 3.x OpenAPI 容灾门面（`XxlJobAdminTemplate`）、告警风暴抑制三阶状态机（首发告警 / 聚合摘要 / 故障自愈）、Spring Boot Actuator 运维管控端点（`/actuator/xxljobs`）、健康探针（`/actuator/health`）、Micrometer 监控度量（`ateng.job.xxl.execution`）及脱网极速调试门面（`XxlJobTestHelper` 支持 POJO 自动序列化绑定与 JSR-303 防卫）。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.job.xxl.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **声明式增强** | 增强任务注解 | `@AtengXxlJob` | `enabled`<br>`context-aspect-enabled` | 声明式标注在 `@XxlJob` 方法上，按需织入四维上下文、本地硬超时、租户模式与降级补偿 | `XxlJobContextAspectTest` / `SimpleDemoJobHandler` |
| **声明式增强** | 任务标识名称 | `@AtengXxlJob.value` | - | `String value()`，指定 JobHandler 标识名称（默认留空，优先以 `@XxlJob.value()` 为准） | `XxlJobContextAspectTest` / `SimpleDemoJobHandler` |
| **声明式增强** | 本地硬超时阈值 | `@AtengXxlJob.timeout` | `default-job-timeout` | `String timeout()`，声明式本地硬超时字符串（如 `200ms`、`30s`），看门狗超限强行中断工作线程 | `LocalWatchdogTest` / `TimeoutFallbackJobHandler` |
| **声明式增强** | 多租户隔离模式 | `@AtengXxlJob.tenantMode` | - | `JobTenantMode tenantMode()`，支持 `IGNORE`（忽略租户）、`PARAM`（入参绑定）、`ITERATE`（全租户遍历） | `XxlJobContextAspectTest` / `TenantJobHandler` |
| **声明式增强** | 超时降级补偿方法 | `@AtengXxlJob.fallback` | - | `String fallback()`，指定同 Bean 降级补偿方法名，任务超时中断后就地异步触发补偿逻辑 | `LocalWatchdogTest` / `TimeoutFallbackJobHandler` |
| **声明式增强** | 方法级告警开关 | `@AtengXxlJob.alertEnabled` | `alert-enabled` | `boolean alertEnabled()`，方法级精细化静音开关，默认 true | `XxlJobAlertAggregatorTest` / `AlertDemoJobHandler` |
| **声明式增强** | 方法级失败抑制阈值 | `@AtengXxlJob.alertSuppressThreshold` | `alert-suppress-threshold` | `int alertSuppressThreshold()`，方法级连续失败告警触发阈值（-1 表示继承全局配置） | `XxlJobAlertAggregatorTest` / `AlertDemoJobHandler` |
| **参数防卫校验** | JSR-303 快速失败 | 方法形参声明 `@Valid` | - | 切面自动将调度 JSON 入参反序列化为形参强类型 DTO 并执行 JSR-303 防卫与 Fail-Fast 阻断 | `XxlJobContextAspectTest` / `ParamValidationJobHandler` |
| **切面拦截责任链** | 任务执行切面拦截器 SPI | `XxlJobInterceptor` | - | 三阶段切面扩展点：`preHandle`、`postHandle`、`afterCompletion`，支持企业级灰度与审计 | `XxlJobContextAspectTest` / `CanaryAuditJobInterceptor` |
| **切面拦截责任链** | 灰度跳过优雅终止 | `XxlJobInterceptor.preHandle` | - | `boolean preHandle(XxlJobExecutionContext context)`，返回 false 时优雅终止并返回 200 SUCCESS 防告警风暴 | `XxlJobContextAspectTest` / `CanaryAuditJobInterceptor` |
| **切面拦截责任链** | 任务执行后置处理 | `XxlJobInterceptor.postHandle` | - | `void postHandle(XxlJobExecutionContext context, Object result)`，任务成功执行后调用 | `XxlJobContextAspectTest` / `CanaryAuditJobInterceptor` |
| **切面拦截责任链** | 任务完成终态清理 | `XxlJobInterceptor.afterCompletion` | - | `void afterCompletion(XxlJobExecutionContext context, Throwable throwable)`，在 finally 块逆序触发终态归档 | `XxlJobContextAspectTest` / `CanaryAuditJobInterceptor` |
| **切面拦截责任链** | 执行上下文模型 | `XxlJobExecutionContext` | - | 携带 `jobHandler`、`jobId`、`traceId`、`tenantId`、`shardIndex`、`jobParam`、`targetMethod` 与扩展属性 | `XxlJobContextAspectTest` / `CanaryAuditJobInterceptor` |
| **运行时治理** | 节点级任务治理总线 | `JobRuntimeGovernor` | - | 内存单例总线，支持在单个节点维度对特定任务实施临时静默与恢复，随 Pod 重启自复位 | `JobRuntimeGovernorTest` / `SampleJobXxlIntegrationTest` |
| **运行时治理** | 暂停任务处理器 | `JobRuntimeGovernor.pause` | - | `void pause(String handler)`，将指定任务加入内存静默名单，被调度触发时直接旁路跳过 | `JobRuntimeGovernorTest` / `SampleJobXxlIntegrationTest` |
| **运行时治理** | 恢复任务处理器 | `JobRuntimeGovernor.resume` | - | `void resume(String handler)`，将指定任务移出静默名单，恢复正常调度执行 | `JobRuntimeGovernorTest` / `SampleJobXxlIntegrationTest` |
| **运行时治理** | 静默状态判定与列表 | `JobRuntimeGovernor.isPaused` | - | `boolean isPaused(String handler)` 与 `Set<String> getPausedHandlers()` 查询当前节点静默清单 | `JobRuntimeGovernorTest` / `XxlJobEndpoint` |
| **超时降级补偿** | 本地硬超时守护时间轮 | `LocalWatchdog` | `default-job-timeout` | 基于 Netty `HashedWheelTimer` 与受管线程工厂，超限强行中断工作线程并广播 `JobTimeoutEvent` | `LocalWatchdogTest` / `TimeoutFallbackJobHandler` |
| **超时降级补偿** | 全局超时降级处理器 SPI | `JobTimeoutFallbackHandler` | - | `void onTimeout(JobExecutionMetadata metadata, Duration elapsed)`，全局超时异步补偿回调 | `LocalWatchdogTest` / `TimeoutFallbackJobHandler` |
| **超时降级补偿** | 任务超时领域事件 | `JobTimeoutEvent` | - | Spring 领域事件，携带 `jobId`、`jobHandler`、`timeout`、`elapsed` 耗时元数据 | `LocalWatchdogTest` |
| **分片流式批处理** | 步长流式顺序消费 | `XxlShardingBatchTemplate` | `batch-size` | `executeStep(initialId, batchSize, queryFunc, idExtractor, consumer, failureHandler)` 游标拉取 | `XxlShardingBatchTemplateTest` / `ShardingBatchJobHandler` |
| **分片流式批处理** | 步长逐条流式消费 | `XxlShardingBatchTemplate` | `batch-size` | `executeStepForEach(initialId, batchSize, queryFunc, idExtractor, itemConsumer, failureHandler)` | `XxlShardingBatchTemplateTest` |
| **分片流式批处理** | 分片智能过滤流式消费 | `XxlShardingBatchTemplate` | `batch-size` | `executeShardedStep(sharding, initialId, batchSize, queryFunc, idExtractor, consumer, failureHandler)` | `XxlShardingBatchTemplateTest` / `ShardingBatchJobHandler` |
| **分片流式批处理** | 步长受管并发消费 | `XxlShardingBatchTemplate` | `batch-max-concurrency` | `executeStepParallel(...)` 结合信号量背压并发分片拉取，透传四维上下文与死信收集 | `XxlShardingBatchTemplateTest` |
| **分片流式批处理** | 分片广播受管并发消费 | `XxlShardingBatchTemplate` | `batch-max-concurrency` | `executeShardedStepParallel(...)` 广播分片智能切片 + 受管线程池并发消费 | `XxlShardingBatchTemplateTest` |
| **分片流式批处理** | 失败切片死信模型 | `FailedBatch<T>` | - | 封装 `items`（失败明细）、`batchIndex`、`cursorId`、`cause`，供补偿回调持久化死信 | `XxlShardingBatchTemplateTest` / `ShardingBatchJobHandler` |
| **分片流式批处理** | 执行结果统计模型 | `BatchExecutionResult` | - | 封装总数、成功数、失败数、批次数、耗时与有界失败集合（`MAX_FAILED_ITEMS=1000` 截断防 OOM） | `XxlShardingBatchTemplateTest` / `ShardingBatchJobHandler` |
| **分片流式批处理** | 实时流式进度打卡 | `XxlShardingBatchTemplate` | `progress-log-interval` | 定期输出结构化进度日志（默认每 5000 条输出一次已推进总数与耗时），消除长任务盲盒 | `XxlShardingBatchTemplateTest` / `ShardingBatchJobHandler` |
| **多租户遍历** | 顺序多租户隔离遍历 | `TenantJobTemplate` | - | `executeForEachTenant(action)`，在独立租户沙箱中流式遍历各租户执行，杜绝数据串写 | `TenantJobTemplateTest` / `TenantJobHandler` |
| **多租户遍历** | 受管并发多租户遍历 | `TenantJobTemplate` | `batch-max-concurrency` | `executeForEachTenantParallel(action)`，基于信号量背压与受管线程池并发遍历，透传上下文 | `TenantJobTemplateTest` / `TenantJobHandler` |
| **多租户遍历** | 租户列表提供者 SPI | `TenantListProvider` | - | `List<String> getTenantIds()`，供业务系统注册有效租户提供者，配合模板遍历 | `TenantJobTemplateTest` / `TenantJobHandler` |
| **ORM 流式扩展** | MyBatis-Plus 游标批处理 | `XxlMyBatisStreamingTemplate` | - | `executeStreaming(...)` 联动 `StreamingQueryTemplate` 与 `Cursor` 顺序/并发流式拉取 | `XxlShardingBatchTemplateTest` |
| **OpenAPI 门面** | 调度任务生命周期管理 | `XxlJobAdminTemplate` | `admin-addresses`<br>`access-token` | `addJob`、`updateJob`、`startJob`、`stopJob`、`removeJob`、`triggerJob`，支持集群多节点故障转移 | `XxlJobAdminTemplateTest` / `SampleJobXxlIntegrationTest` |
| **脱网极速调试** | 脱网单测手动触发 | `XxlJobTestHelper` | - | `trigger(handler, paramObject, shardIndex, shardTotal, tenantId)`，支持 POJO 自动序列化与 JSR-303 防卫 | `XxlJobTestHelperTest` / `SampleJobXxlIntegrationTest` |
| **运维端点** | 全量 Handler 状态清单 | `XxlJobEndpoint` | `enabled` | `GET /actuator/xxljobs`，列出已注册的 JobHandler 概览、执行器端口、元数据及静默状态 | `XxlJobEndpointTest` / `SampleJobXxlIntegrationTest` |
| **运维端点** | 单 Handler 详情与诊断 | `XxlJobEndpoint` | `enabled` | `GET /actuator/xxljobs/{handler}`，查询指定 Handler 的反射元数据、目标类与当前静默状态 | `XxlJobEndpointTest` / `SampleJobXxlIntegrationTest` |
| **运维端点** | 动态节点治理操作 | `XxlJobEndpoint` | `enabled` | `POST /actuator/xxljobs/{handler}/pause` 与 `/resume`，在线一键暂停与恢复单节点任务 | `XxlJobEndpointTest` / `SampleJobXxlIntegrationTest` |
| **运维端点** | 动态脱网运维触发 | `XxlJobEndpoint` | `enabled` | `POST /actuator/xxljobs/{handler}`，请求体传入 param 与分片参数，在线手动触发 | `XxlJobEndpointTest` / `SampleJobXxlIntegrationTest` |
| **执行器加固** | 生产增强执行器 | `AtengXxlJobSpringExecutor` | `admin-addresses`<br>`appname`<br>`port`<br>`ip`<br>`address` | 继承官方执行器，装配三阶停机排空、CIDR 防火墙与端口冲突顺延自愈 | `AtengXxlJobSpringExecutorTest` / `SampleJobXxlApplication` |
| **执行器加固** | 优雅停机状态机 | `AtengXxlJobSpringExecutor.stop()` | `drain-timeout` | 阶段一主动注销（`registryRemove`）-> 阶段二轮询排空 -> 阶段三物理销毁 Netty | `AtengXxlJobSpringExecutorTest` |
| **安全防火墙** | 入站 CIDR 白名单 | `XxlJobFirewallInboundHandler` | `cidr-whitelist` | 支持单个 IP 与 CIDR 掩码段（如 `192.168.0.0/16`），非白名单入站连接直接阻断 | `XxlJobFirewallTest` / `AtengEmbedServerTest` |
| **安全防火墙** | 令牌防空鉴权 | `XxlJobFirewallInboundHandler` | `access-token`<br>`require-access-token` | 校验调度请求头中的 AccessToken，支持启动期未配置令牌时 Fail-Fast 快速拦截 | `XxlJobFirewallTest` / `AtengXxlJobAutoConfigurationTest` |
| **告警自愈** | 告警风暴抑制器 | `XxlJobAlertAggregator` | `alert-enabled`<br>`alert-suppress-duration`<br>`alert-suppress-threshold` | 聚合失败异常，首发立即告警 -> 抑制窗口内静默聚合为富文本摘要 -> 成功时自愈恢复 | `XxlJobAlertAggregatorTest` / `SampleJobXxlIntegrationTest` |
| **告警自愈** | 告警领域事件 | `XxlJobAlertEvent` | - | Spring 标准领域事件，携带 `alertType` (`INITIAL_FAILURE` / `FAILURE_SUMMARY` / `RECOVERY`)、`failureCount`、`throwable` | `XxlJobAlertAggregatorTest` / `SampleJobXxlIntegrationTest` |
| **健康探针** | 执行器健康状态检测 | `XxlJobHealthIndicator` | `enabled` | 暴露于 `/actuator/health`，优先读取执行器运行时顺延自愈端口与 IP，上报网络连通性 | `XxlJobEndpointTest` / `SampleJobXxlIntegrationTest` |
| **监控度量** | Micrometer 核心指标 | `XxlJobMetricsBinder` | - | 发布 `ateng.job.xxl.execution` Timer 指标（Throughput、Failures、P50/P95/P99 分位数耗时） | `XxlJobEndpointTest` / `SampleJobXxlIntegrationTest` |
| **日志镜像** | SLF4J 双写动态桥接 | `XxlJobLogbackAppenderRegistrar` | `log-mirror-enabled` | 自动向 Logback Root Logger 挂载动态 Appender，在调度线程将日志镜像至调度中心 | `XxlJobLogbackAppenderRegistrarTest` / `SimpleDemoJobHandler` |
| **日志治理** | 历史日志防崩安全清理 | `AtengJobLogFileCleanHelper` | `log-path`<br>`log-retention-days` | 重构历史日志清理器，强制执行路径防卫与跨平台文件句柄防崩容错 | `AtengJobLogFileCleanHelperTest` |
| **上下文传播** | 调度上下文异步全链路传播 | `XxlJobContextSnapshotProvider` | - | 实现 `AsyncContextSnapshotProvider` SPI，治理 `InheritableThreadLocal` 跨池浅拷贝污染 | `XxlJobContextSnapshotProviderTest` |
| **自动装配** | 模块自动装配入口 | `AtengXxlJobAutoConfiguration` | `enabled` | Spring Boot 3 标准自动装配，平滑探测调度中心配置，标配完整缺省 Bean 守卫 | `AtengXxlJobAutoConfigurationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-job-xxl` 是专为分布式生产级微服务与定时调度集群打造的高可靠、高韧性执行器基础设施 Starter：
- **纯原生零臃肿底座**：100% 依托 Spring Boot 3 与 JDK 17 原生能力，零引入第三方全量工具包；
- **三阶平滑停机排空状态机**：服务停机时先向调度中心发起 `registryRemove` 主动注销停止派发新任务，随后根据 `drain-timeout` 轮询等待活跃任务排空，最后销毁 Netty 物理句柄，杜绝停机杀任务引发的数据脏状态；
- **任务切面拦截器 SPI (`XxlJobInterceptor`)**：提供 `preHandle`（返回 false 时优雅跳过并上报 200 SUCCESS 防告警风暴）、`postHandle` 与 `afterCompletion` 终态归档三阶段扩展；
- **节点级任务运行时治理总线 (`JobRuntimeGovernor`)**：单节点内存动态静默（`pause`）与恢复（`resume`），配合 Actuator 端点实现分钟级单节点故障隔离，随 Pod 重启自复位；
- **入站 CIDR 防火墙与令牌防空**：原生内置 CIDR IP 白名单安全防火墙与 AccessToken 快速失败防卫，彻底防御内网非法渗透与未授权伪造调度；
- **四维上下文全链路透传**：每次调度自动生成 32 位无锁 MDC `traceId`，无缝继承租户模式（`IGNORE` / `PARAM` / `ITERATE`）与操作人信息，并在异步子任务与分片消费终态安全清理现场；
- **大数据流式游标防 OOM 模板**：提供 `XxlShardingBatchTemplate`，基于主键步长流式分批与背压并发消费，内置 `FailedBatch<T>` 失败切片收集、实时进度打卡（`progress-log-interval`）与 `MAX_FAILED_ITEMS=1000` 截断防 OOM；
- **本地硬看门狗与两级降级补偿**：守护时间轮超限强中断（`interrupt()`），支持同 Bean 就地降级（`@AtengXxlJob.fallback`）与全局 `JobTimeoutFallbackHandler` SPI 补偿；
- **多租户隔离与受管并发遍历**：提供 `TenantJobTemplate`，支持顺序遍历与 `executeForEachTenantParallel` 信号量背压并发遍历，杜绝数据串写；
- **告警风暴抑制与故障自愈**：失败首发告警、静默期内自动收敛为富文本聚合摘要卡片、故障修复后自动发送绿色自愈通知；
- **云原生运维度量与脱网极速调试**：内置 `/actuator/xxljobs` 运维端点、`/actuator/health` 健康探针、Micrometer 百分位监控指标与 `XxlJobTestHelper` 本地脱网 POJO 自动绑定测试门面。

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
        progress-log-interval: 5000                        # 分片批处理实时进度打卡条数步长

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

### 3.1 JSR-303 强类型参数校验与 POJO 自动绑定

在分布式调度中，调度中心传入的 JSON 参数往往缺乏格式约束。开发者只需在方法形参上直接声明经过 JSR-303 注解标注的 DTO，框架切面将自动从 `JobParam` 反序列化并执行无状态格式校验；校验不通过直接快速失败中断，记录结构化错误日志并触发首发告警：

```java
// 1. 声明强类型参数 DTO
package io.github.atengk.boot.sample.job.xxl.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;

public class UserSyncParam implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "同步批次号 batchNo 不能为空")
    private String batchNo;

    @NotNull(message = "批大小 batchSize 不能为空")
    @Min(value = 1, message = "批大小不能小于 1")
    @Max(value = 1000, message = "单批同步上限不能超过 1000")
    private Integer batchSize;

    private String tenantId;
    // Getters & Setters...
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

### 3.2 大数据分片广播、流式防 OOM 与死信补偿回调 (`XxlShardingBatchTemplate`)

针对千万级大表与分布式广播分片场景，框架提供 `XxlShardingBatchTemplate`，在方法形参注入 `ShardingContext`，基于主键步长流式切片消费，内置失败切片死信收集（`FailedBatch<T>`）与实时进度打卡：

```java
@Component
public class ShardingBatchJobHandler {

    private static final Logger log = LoggerFactory.getLogger(ShardingBatchJobHandler.class);
    private final XxlShardingBatchTemplate batchTemplate;
    private final List<FailedBatch<MockOrder>> deadLetters = new CopyOnWriteArrayList<>();

    public ShardingBatchJobHandler(XxlShardingBatchTemplate batchTemplate) {
        this.batchTemplate = batchTemplate;
    }

    @XxlJob("shardingBatchDeadLetterJob")
    @AtengXxlJob(value = "shardingBatchDeadLetterJob", timeout = "30s")
    public void executeShardingBatchWithDeadLetter(ShardingContext shardingContext) {
        log.info(">>>>>> [ShardingBatchJobHandler] 开始执行带死信补偿回调的分片批处理任务...");
        deadLetters.clear();

        BatchExecutionResult summary = batchTemplate.executeShardedStep(
                shardingContext,
                0L,
                5,   // 每批 5 条
                15L, // 预期总条数 15 条，用于结构化进度打卡
                false, // failFast = false: 容错继续处理其余切片并收集死信
                (lastId, limit) -> orderMapper.selectNextBatch(lastId, limit),
                MockOrder::id,
                batch -> {
                    for (MockOrder order : batch) {
                        if (order.id() == 8L) {
                            throw new RuntimeException("订单 [ID=" + order.id() + "] 模拟业务处理失败");
                        }
                    }
                },
                failedBatch -> {
                    log.warn(">>>>>> [DeadLetter] 捕获失败切片进入死信回调: 批次 {}, 失败条数 {}",
                            failedBatch.getBatchIndex(), failedBatch.getItems().size());
                    deadLetters.add(failedBatch);
                }
        );

        XxlJobHelper.handleSuccess(String.format("分片批处理完成: 成功 %d 条, 失败 %d 条, 死信收集 %d 批",
                summary.getSuccessCount(), summary.getFailCount(), deadLetters.size()));
    }

    public record MockOrder(Long id, String orderNo, Integer amount) {}
}
```

### 3.3 任务生命周期拦截器与金丝雀灰度跳过 (`XxlJobInterceptor`)

实现 `XxlJobInterceptor` SPI，可非侵入式织入节点准入预检、金丝雀灰度放行与终态审计。当 `preHandle` 返回 `false` 时，框架优雅终止执行并直接报告 `200 SUCCESS`，防御管理台告警风暴：

```java
@Component
public class CanaryAuditJobInterceptor implements XxlJobInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CanaryAuditJobInterceptor.class);

    @Override
    public boolean preHandle(XxlJobExecutionContext context) throws Exception {
        String jobName = context.getJobName();

        // 灰度拦截跳过规则：若任务名为 canarySkipJob，则优雅拦截并直接报告 200 SUCCESS
        if ("canarySkipJob".equalsIgnoreCase(jobName)) {
            log.warn(">>>>>> [CanaryAuditJobInterceptor] 任务 [{}] 命中灰度拦截跳过规则，优雅终止执行！", jobName);
            return false;
        }

        log.info(">>>>>> [CanaryAuditJobInterceptor] [preHandle] 任务 [{}] 准入审计放行, traceId: {}",
                jobName, context.getTraceId());
        return true;
    }

    @Override
    public void afterCompletion(XxlJobExecutionContext context, Throwable throwable) throws Exception {
        log.info(">>>>>> [CanaryAuditJobInterceptor] [afterCompletion] 任务 [{}] 执行终态归档, 异常: {}",
                context.getJobName(), throwable != null ? throwable.getMessage() : "none");
    }
}
```

### 3.4 节点级任务运行时治理与 Actuator 动态启停 (`JobRuntimeGovernor`)

单节点遇到异常时，无需停止整个微服务，可通过治理总线或 Actuator 端点临时静默该节点上的特定任务：

```java
// 1. 在业务代码中通过 JobRuntimeGovernor 编程式治理
@Autowired
private JobRuntimeGovernor governor;

public void isolateNodeJob(String handlerName) {
    governor.pause(handlerName); // 暂停该任务在当前节点的执行
    boolean paused = governor.isPaused(handlerName); // 状态查询
    governor.resume(handlerName); // 恢复调度
}
```

运维可通过 Actuator HTTP 端点在线执行（配合监控平台实现自愈自动化）：
- **暂停单节点任务**：`POST /actuator/xxljobs/{handler}/pause`
- **恢复单节点任务**：`POST /actuator/xxljobs/{handler}/resume`
- **巡检静默清单**：`GET /actuator/xxljobs`（响应体包含 `pausedCount` 与 `pausedHandlers`）

### 3.5 本地硬看门狗超时强中断与同 Bean 就地降级补偿

在 `@AtengXxlJob` 中配置毫秒/秒级硬看门狗超时阈值与 `fallback` 降级补偿方法名。当任务超时被看门狗强行中断（`interrupt()`）后，框架切面自动路由至指定的同 Bean 降级补偿方法：

```java
@Component
public class TimeoutFallbackJobHandler {

    private static final Logger log = LoggerFactory.getLogger(TimeoutFallbackJobHandler.class);

    @XxlJob("timeoutFallbackDemoJob")
    @AtengXxlJob(value = "timeoutFallbackDemoJob", timeout = "200ms", fallback = "onTimeoutFallback")
    public void executeSlowJob() {
        log.info(">>>>>> [TimeoutFallbackJobHandler] 开始执行慢任务，预期被看门狗中断...");
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            log.warn(">>>>>> [TimeoutFallbackJobHandler] 捕获到硬看门狗线程中断信号并优雅退出！");
            XxlJobHelper.handleSuccess("任务超时被看门狗中断，触发就地降级补偿: timeoutFallbackDemoJob");
            return;
        }
        XxlJobHelper.handleSuccess("未超时正常执行完成");
    }

    /**
     * 就地降级补偿方法：支持自动注入 JobExecutionMetadata 与实际已耗时 Duration
     */
    public void onTimeoutFallback(JobExecutionMetadata metadata, Duration elapsed) {
        log.warn(">>>>>> [TimeoutFallbackJobHandler] 触发就地降级补偿回调! 任务: {}, 耗时: {}ms",
                metadata.getJobName(), elapsed.toMillis());
        // 执行对账冲正、释放分布式锁或上报审计事件...
    }
}
```

### 3.6 多租户隔离全模态与受管并发遍历 (`TenantJobTemplate`)

框架提供声明式与编程式双轨多租户调度支持，具备 `executeForEachTenantParallel` 信号量背压受管并发遍历：

```java
@Component
public class TenantJobHandler {

    private final TenantJobTemplate tenantJobTemplate;

    public TenantJobHandler(TenantJobTemplate tenantJobTemplate) {
        this.tenantJobTemplate = tenantJobTemplate;
    }

    // 1. 声明式：全局系统级任务（忽略租户隔离）
    @XxlJob("globalArchiveJob")
    @AtengXxlJob(value = "globalArchiveJob", tenantMode = JobTenantMode.IGNORE)
    public void executeGlobalArchive() {
        boolean ignoreTenant = TenantContextHolder.isIgnoreTenant(); // true
        XxlJobHelper.handleSuccess("全局归档成功, ignoreTenant=" + ignoreTenant);
    }

    // 2. 声明式：参数绑定单租户任务（从任务入参解析并挂载当前租户）
    @XxlJob("tenantScopedJob")
    @AtengXxlJob(value = "tenantScopedJob", tenantMode = JobTenantMode.PARAM)
    public void executeTenantScoped() {
        String currentTenant = TenantContextHolder.getTenantId();
        XxlJobHelper.handleSuccess("特定租户任务完成, 当前租户=" + currentTenant);
    }

    // 3. 编程式：全租户沙箱受管并发遍历
    @XxlJob("parallelTenantJob")
    @AtengXxlJob(value = "parallelTenantJob", tenantMode = JobTenantMode.ITERATE)
    public void executeParallelTenant() {
        BatchExecutionResult result = tenantJobTemplate.executeForEachTenantParallel(tenantId -> {
            String currentTenant = TenantContextHolder.getTenantId(); // 严格等于 tenantId
            billingService.generateMonthlyBill(currentTenant);
        });

        XxlJobHelper.handleSuccess(String.format("多租户并发遍历完成: 总数 %d, 成功 %d, 失败 %d",
                result.getTotalCount(), result.getSuccessCount(), result.getFailCount()));
    }
}
```

### 3.7 OpenAPI 集群管理门面 (`XxlJobAdminTemplate`)

依托 XXL-Job 3.x 官方 `AdminJobBiz` OpenAPI，提供强类型、无状态的任务编排门面，支持多调度中心节点自动故障转移（Failover Retry）：

```java
@Autowired
private XxlJobAdminTemplate adminTemplate;

public void dynamicScheduleOperations() {
    // 1. 新增调度任务
    JobAddRequest addRequest = new JobAddRequest();
    addRequest.setJobGroup(1);
    addRequest.setName("动态报表生成任务");
    addRequest.setExecutorHandler("reportGenerateJob");
    addRequest.setScheduleType("CRON");
    addRequest.setScheduleConf("0 0 2 * * ?");
    int newJobId = adminTemplate.addJobAndGetId(addRequest);

    // 2. 启停调度任务
    adminTemplate.startJob(newJobId);
    adminTemplate.stopJob(newJobId);

    // 3. 即时手动触发一次调度
    adminTemplate.triggerJob(newJobId, "param-20261102");

    // 4. 删除调度任务
    adminTemplate.removeJob(newJobId);
}
```

### 3.8 脱网极速调试门面 (`XxlJobTestHelper`)

在无外部调度中心的离线单测或本地开发环境下，使用 `XxlJobTestHelper` 静态门面触发任务，支持 POJO 自动序列化为 JSON 并触发 JSR-303 防卫校验：

```java
@Test
void testOfflineExecution() {
    // 1. 无参手动触发
    XxlJobTestResult result = XxlJobTestHelper.trigger("simpleGreetingJob");
    assertTrue(result.isSuccess());
    assertEquals(200, result.handleCode());

    // 2. 强类型 POJO 自动序列化并触发 JSR-303 防卫校验
    UserSyncParam param = new UserSyncParam();
    param.setBatchNo("BATCH-20261102-001");
    param.setBatchSize(200);
    param.setTenantId("TENANT-1001");

    XxlJobTestResult pojoResult = XxlJobTestHelper.trigger("userSyncJob", param);
    assertTrue(pojoResult.isSuccess());
    assertEquals(200, pojoResult.handleCode());

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

### 4.3 堆内存防 OOM 刚性熔断不变量 (`MAX_FAILED_ITEMS = 1000`)
- 在千万级数据分片处理中，若下游存储发生全局故障，可能会连续产生数万条失败明细；
- 为防止将海量失败实体缓存在 `BatchExecutionResult` 中引发 JVM 堆内存溢出（OOM），框架在 `BatchExecutionResult` 中硬编码刚性上限 `MAX_FAILED_ITEMS = 1000`；溢出部分自动截断并置位 `truncatedFailures = true`，同时完整保留 `failCount` 失败计数，坚守内存确定性生命周期防线。

### 4.4 拦截器返回值语义规范 (`preHandle` 返回 `false` 报告 200 SUCCESS)
- 在微服务集群进行金丝雀灰度发布或节点预检时，若某节点判定该任务不应在本机执行，拦截器 `preHandle` 返回 `false`；
- 框架与 XXL-Job 调度中心协议达成共识：此时切面自动拦截并向调度中心报告 `200 SUCCESS` 并在消息中标记 `[INTERCEPTED]`，防止调度中心判定为网络或业务故障引发重试与报警风暴。

### 4.5 端口冲突顺延自愈与多网卡智能探测
- **端口冲突自愈**：当配置的 Netty 端口（默认 9999）已被其他服务占用时，`XxlJobPortResolver` 自动以 `portDetectionMaxRetry`（默认 100）为步长顺序探测可用空闲端口绑定，杜绝单机多实例因端口冲突无法启动；
- **容器与网卡探测**：未显式配置 `ip` 时，框架优先探测 `POD_IP` 环境变量，未配置时遍历网卡剔除回环、虚拟与未激活网卡，确保向调度中心上报真实可通 IP。

### 4.6 告警风暴抑制三阶状态机与状态自愈
- **首发告警 (`INITIAL_FAILURE`)**：当任务首次执行失败时，立即投递首发告警，确保值班人员第一时间获知故障；
- **静默期聚合 (`FAILURE_SUMMARY`)**：在 `alert-suppress-duration`（默认 5 分钟）与 `alert-suppress-threshold`（默认 10 次）双重窗口内抑制单次重复骚扰；在窗口期满或步长溢出时，触发包含累计失败总数与最新异常的聚合卡片，随后重置阶段步长重新进入静默；
- **故障自愈恢复 (`RECOVERY`)**：当任务在经历故障后首次执行成功时，自动发出绿色自愈卡片，汇报期间累计失败总数与历时，清除故障计数，闭环自愈状态机。

### 4.7 Actuator 监控端点与 Micrometer 度量
- **Actuator 运维端点**：
  - `GET /actuator/xxljobs`：获取所有受管 `@XxlJob` 列表、增强元数据与单节点静默状态；
  - `GET /actuator/xxljobs/{handler}`：查询指定 Handler 的反射元数据、目标 Bean、方法签名与静默状态；
  - `POST /actuator/xxljobs/{handler}`：在线脱网运维手动触发一次执行；
  - `POST /actuator/xxljobs/{handler}/pause`：单节点运行时动态暂停任务；
  - `POST /actuator/xxljobs/{handler}/resume`：单节点运行时动态恢复任务；
- **健康探针 (`/actuator/health`)**：
  - 探针 `XxlJobHealthIndicator` 动态探测执行器运行时顺延自愈端口与 IP，上报网络监听连通性；
- **Micrometer 指标**：
  - 发布 `ateng.job.xxl.execution` Timer 度量，携带 `handler`、`status`（success / failure）、`tenant` 标签，全真上报吞吐量与耗时分布。
