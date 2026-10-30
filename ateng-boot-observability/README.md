# ateng-boot-observability 云原生可观测性与端点安全治理 Starter

> 基于 Spring Boot 3 Actuator、Micrometer Prometheus 与 JDK 17 原生并发能力构建的工业级云原生可观测性、健康探针防假死与端点安全治理底座。提供受管线程池动态度量与任务拒绝追踪绑定器（`AtengThreadPoolMeterBinder`、`TrackingRejectedExecutionHandler`）、防假死异步受管健康探针基类（`AbstractSafeHealthIndicator`）与全中间件安全探针体系、业务度量双驱门面（`MetricsTemplate` 与声明式 `@ObservedMetric`）、零依赖 CIDR IP 白名单免密穿透与 Basic Auth / Token 凭据互斥核验双层过滤器（`ActuatorEndpointSecurityFilter`）、Kubernetes 存活与就绪探针黄金硬解耦治理（`ObservabilityEnvironmentPostProcessor`）以及全生命周期运维领域事件总线。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.observability.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **线程池度量** | 受管线程池全量自动发现与动态打标绑定器 | `AtengThreadPoolMeterBinder` | `metrics.prometheus.enabled`<br>`metrics.thread-pool.enabled` | `bindTo(MeterRegistry registry)`：容器启动时自动扫描全量 `ThreadPoolTaskExecutor` 与 `ThreadPoolExecutor`，打标 `pool="<beanName>"` 并向 Prometheus 导出核心、最大、活跃、队列深度及拒绝任务指标 | `AtengThreadPoolMeterBinderTest` / `ObservabilityScenarioTest` |
| **任务拒绝追踪** | 线程池任务拒绝原子累加追踪与异常安全装饰器 | `TrackingRejectedExecutionHandler` | - | `rejectedExecution(Runnable r, ThreadPoolExecutor executor)`<br>`getCumulativeRejectedCount()`<br>`getDelegate()`：装饰器透明包装原生策略，防 NPE 校验，原子累加导出 `ateng_threadpool_rejected_tasks_total`，并向总线广播 `ThreadPoolRejectedEvent` | `TrackingRejectedExecutionHandlerTest` / `ObservabilityScenarioTest` |
| **业务度量门面** | 编程式业务指标度量统一门面 | `MetricsTemplate` | `metrics.prometheus.enabled` | `increment(String name, double amount, String... tags)`<br>`summary(String name, double amount, String... tags)`<br>`record(String name, Duration duration, String... tags)`<br>`gauge(String name, T stateObject, ToDoubleFunction<T> valueFunction, String... tags)`：Micrometer 强类型统一封装，自动执行标签键值对防空与奇数截断防卫 | `MetricsTemplateTest` / `ObservabilityScenarioTest` |
| **声明式度量** | 声明式方法耗时与状态性能观测切面 | `@ObservedMetric`<br>`ObservedMetricAspect` | `metrics.prometheus.enabled` | `@ObservedMetric(value = "order.create", tags = {"tier", "api"}, percentiles = {0.95, 0.99})`：AOP 环绕切面透明测量执行耗时、分位数分布、成功/失败分别递增计数器及异常类名打标，异常时静默降级不阻断业务 | `ObservedMetricAspectTest` / `ObservabilityScenarioTest` |
| **探针防假死基类** | 生产级受管健康探针超时硬切断抽象基类 | `AbstractSafeHealthIndicator` | `health.timeout`（默认 `2s`） | `health()`<br>`doCheck(Health.Builder builder)`<br>`getTimeout()`<br>`getExecutor()`：将子探针隔离在独立受管线程池中异步探测，超时立即硬切断降级返回 `Status.UNKNOWN`（带 `status=TIMEOUT`），异常返回 `Status.DOWN`，并广播 `HealthProbeDegradedEvent` | `AbstractSafeHealthIndicatorTest` / `ObservabilityScenarioTest` |
| **Redis 探针** | Redis / Redisson 弱依赖防假死健康探针 | `RedisSafeHealthIndicator` | `health.redis.enabled` | 优先通过 `RedisConnection.ping()` 执行轻量弱探测，未配置时自动回退为 `UNKNOWN`，兼顾离线自洽与真实探测，超时降级与事件广播 | `RedisSafeHealthIndicatorTest` |
| **Kafka 探针** | Kafka 生产与管理客户端防假死健康探针 | `KafkaSafeHealthIndicator` | `health.kafka.enabled` | 优先通过 `AdminClient.describeCluster(...)` 弱探测集群节点可用性与节点数，离线自适应与超时硬切断 | `KafkaSafeHealthIndicatorTest` |
| **RabbitMQ 探针** | RabbitMQ Broker 连通性防假死健康探针 | `RabbitmqSafeHealthIndicator` | `health.rabbitmq.enabled` | 探测 `ConnectionFactory.createConnection()` 与 Channel 可用性，超时切断防阻塞 | `RabbitmqSafeHealthIndicatorTest` |
| **Search 探针** | Elasticsearch / OpenSearch 双驱防假死健康探针 | `SearchSafeHealthIndicator` | `health.search.enabled` | 调用 `ElasticsearchClient.cluster().health(...)` 探测底层集群健康状态，超时降级与事件广播 | `SearchSafeHealthIndicatorTest` |
| **存储探针** | 对象存储本地目录与 S3 连通性探针 | `StorageHealthIndicator` | `health.storage.enabled` | 弱探测 `StorageDriverFactory` 与 `StorageDriver`，校验本地目录存在与写入权限或 S3 连通性，敏感凭据正则脱敏 | `StorageHealthIndicatorTest` / `ObservabilityScenarioTest` |
| **通信探针** | 实时通信 WebSocket/SSE 双通道会话探针 | `RealtimeHealthIndicator` | `health.realtime.enabled` | 弱探测 `RealtimeSessionManager` 活跃会话数、在线用户数及 `RealtimeClusterBroadcaster` 节点状态 | `RealtimeHealthIndicatorTest` |
| **数据源探针** | 动态多数据源与连接池连通性探针 | `DynamicDatasourceHealthIndicator` | `health.dynamic-datasource.enabled` | 探查连接池 `isValid(1)`，联动 `DynamicDatasourceGuard` 诊断多数据源与 `DynamicRoutingDataSource` 状态 | `DynamicDatasourceHealthIndicatorTest` / `ObservabilityScenarioTest` |
| **RocketMQ 探针** | RocketMQ 5.x 客户端与消费监听容器探针 | `RocketmqHealthIndicator` | `health.rocketmq.enabled` | 弱探测 `RocketmqTemplate` 的 `Producer` 实例及 `RocketmqConsumerContainer` 的消费运行状态与活跃端点数 | `RocketmqHealthIndicatorTest` |
| **CIDR 匹配器** | 纯 JDK 原生零依赖 CIDR IP 范围匹配器 | `CidrMatcher` | - | `matches(String clientIp, String cidrOrIp)`<br>`matchesAny(String clientIp, Iterable<String> cidrsOrIps)`：支持 IPv4/IPv6 网段掩码与单 IP 精确匹配，位运算极速无锁解析，无任何第三方包依赖 | `CidrMatcherTest` / `ObservabilityScenarioTest` |
| **双层安全守卫** | Actuator 端点生产加固与双层认证过滤器 | `ActuatorEndpointSecurityFilter` | `security.lockdown-enabled`<br>`security.token`<br>`security.allowed-ip-ranges`<br>`security.basic-auth.*` | 优先级 `HIGHEST_PRECEDENCE + 20`，白名单端点免密放行；敏感端点执行双层防卫：层 1 命中 CIDR 白名单直接放行；层 2 校验 Basic Auth 与 `X-Actuator-Token`（OR 逻辑），恒定时间比对防时序攻击，拦截时返回 403 并广播 `ActuatorAccessDeniedEvent` | `ActuatorEndpointSecurityFilterTest` / `ObservabilityScenarioTest` |
| **K8s 探针硬隔离** | 生产安全基线与 K8s 黄金探针解耦后处理器 | `ObservabilityEnvironmentPostProcessor` | `ateng.boot.observability.enabled` | 启动早期最低优先级注入：Liveness 仅包含 `livenessState,ping`（外部依赖物理剥离，彻底杜绝 Pod 死亡螺旋），Readiness 聚合外部全量组件，暴露核心端点 | `ObservabilityEnvironmentPostProcessorTest` / `ObservabilityScenarioTest` |
| **拒绝领域事件** | 线程池任务打满饱和拒绝领域事件 | `ThreadPoolRejectedEvent` | - | 继承 Spring `ApplicationEvent`，携带 `poolName`、`cumulativeRejectedCount`、`taskDescription`、`corePoolSize`、`maxPoolSize`、`activeCount`、`queueSize`、`remainingQueueCapacity` 完整快照，供告警中台秒级感知 | `TrackingRejectedExecutionHandlerTest` / `ObservabilityScenarioTest` |
| **降级领域事件** | 健康探针探测超时或异常降级领域事件 | `HealthProbeDegradedEvent` | - | 继承 Spring `ApplicationEvent`，携带 `indicatorClass`、`componentName`、`status`（`TIMEOUT` / `DOWN`）、`costMs`、`reason`、`cause`，供运维看板实时感知探针抖动 | `AbstractSafeHealthIndicatorTest` / `ObservabilityScenarioTest` |
| **安全拦截事件** | Actuator 敏感端点非法访问阻断领域事件 | `ActuatorAccessDeniedEvent` | - | 继承 Spring `ApplicationEvent`，携带 `uri`、`clientIp`、`reason`、`timestamp`，供安全审计与防扫描告警中台联动 | `ActuatorEndpointSecurityFilterTest` / `ObservabilityScenarioTest` |
| **强类型配置属性** | 全局强类型配置属性类与 IDE 补全元数据 | `ObservabilityProperties` | `ateng.boot.observability.*` | 强类型映射，涵盖 `enabled`、`metrics.*`、`health.timeout`（`Duration`）、`health.<subsystem>.enabled`、`security.lockdown-enabled`、`security.token`、`security.allowed-ip-ranges`、`security.basic-auth.*` | `ObservabilityPropertiesTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-observability` 是面向云原生微服务、容器化调度与高可用集群的企业级全场景可观测性与安全治理底座：
- **K8s 探针硬分级隔离**：遵循云原生运维黄金法则，存活探针（`/actuator/health/liveness`）仅由内部存活状态和 ping 构成，外部依赖全剥离至就绪探针（`/actuator/health/readiness`），彻底消除外部中间件抖动导致的 Pod 误杀死亡螺旋；
- **探针防假死异步硬切断**：所有外部组件探针统一依托受管线程池异步探测，配备强类型超时阈值（默认 `2s`）与降级状态机，杜绝网络假死拖垮 Actuator；
- **业务度量双驱体系**：提供统一编程式门面 `MetricsTemplate` 与声明式切面注解 `@ObservedMetric`，开箱即用支持耗时分位数（P50/P95/P99）、Counter 计数、Gauge 监控与高基数防卫；
- **双层轻量级端点安全防御**：零依赖纯原生 CIDR IP 白名单免密穿透与 HTTP Basic Auth / `X-Actuator-Token` 凭据互斥校验，恒定时间比对防时序攻击，拒绝引入臃肿的 Spring Security；
- **全生命周期运维事件总线**：端点未授权拦截、探针超时降级、线程池任务拒绝全量抽象为标准化 Spring 领域事件，支撑企业级告警中心联动响应；
- **100% 内存离线自洽**：基于 Mock 与嵌入式测试环境，秒级全绿。

### Maven 坐标引入
```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-observability</artifactId>
</dependency>
```

---

## 2. 维度一：最小起步体验 (Happy Path & Out-of-the-Box)

### 场景痛点
1. 默认 Spring Boot Actuator 的健康检查在下游中间件发生网络挂起时会同步阻塞，导致 Actuator 独占工作线程池迅速耗尽并引发 Kubernetes Liveness 存活探针失败误杀容器；
2. 敏感端点（如 `/actuator/env`）一旦意外暴露极易泄露数据库凭据；
3. 业务层度量打点繁琐，缺少开箱即用的注解切面与受管线程池指标自动导出。

### 最小可用配置 (`application.yml`)
```yaml
ateng:
  boot:
    observability:
      enabled: true
      metrics:
        prometheus:
          enabled: true
        thread-pool:
          enabled: true
      health:
        timeout: 2s
        storage:
          enabled: true
        dynamic-datasource:
          enabled: true
      security:
        lockdown-enabled: true
        token: "obs-admin-secret-token"
        allowed-ip-ranges:
          - "10.0.0.0/8"
          - "192.168.1.100"
        basic-auth:
          enabled: true
          username: "admin"
          password: "obs-admin-pass-123"

# 暴露关键端点供 Prometheus 抓取与运维巡检
management:
  endpoints:
    web:
      base-path: /actuator
      exposure:
        include: "health,info,prometheus,env,beans"
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
```

### 开箱极简代码示例

#### 1. 业务指标编程式与声明式打点
```java
@RestController
@RequestMapping("/api/demo")
public class ObservabilityDemoController {

    private final MetricsTemplate metricsTemplate;

    public ObservabilityDemoController(MetricsTemplate metricsTemplate) {
        this.metricsTemplate = metricsTemplate;
    }

    /**
     * 编程式指标打点
     */
    @GetMapping("/metrics/programmatic")
    public Result<String> programmaticMetric() {
        metricsTemplate.increment("sample.orders.created", 1.0, "channel", "app");
        metricsTemplate.summary("sample.orders.amount", 99.5, "channel", "app");
        metricsTemplate.record("sample.orders.process.time", Duration.ofMillis(25), "channel", "app");
        return Result.success("编程式指标打点成功");
    }

    /**
     * 声明式注解 @ObservedMetric 指标打点
     */
    @GetMapping("/metrics/declarative")
    @ObservedMetric(value = "sample.demo.observed", tags = {"tier", "api"}, percentiles = {0.95, 0.99})
    public Result<String> declarativeMetric() {
        return Result.success("声明式指标记录成功");
    }
}
```

#### 2. Prometheus 端点抓取指标结果
访问 `/actuator/prometheus` 即可抓取到受管线程池与业务指标：
```text
# HELP sample_orders_created_total
# TYPE sample_orders_created_total counter
sample_orders_created_total{channel="app",} 1.0

# HELP sample_demo_observed_seconds_count Observed method execution timer
# TYPE sample_demo_observed_seconds_count counter
sample_demo_observed_seconds_count{class="ObservabilityDemoController",method="declarativeMetric",status="success",tier="api",} 1.0

# HELP sample_demo_observed_seconds Observed method execution timer
# TYPE sample_demo_observed_seconds summary
sample_demo_observed_seconds{class="ObservabilityDemoController",method="declarativeMetric",quantile="0.95",status="success",tier="api",} 0.0012
sample_demo_observed_seconds{class="ObservabilityDemoController",method="declarativeMetric",quantile="0.99",status="success",tier="api",} 0.0012

# HELP ateng_threadpool_core_size The core number of threads
# TYPE ateng_threadpool_core_size gauge
ateng_threadpool_core_size{pool="sampleTaskExecutor",type="platform"} 4.0
```

---

## 3. 维度二：生产扩展接管 (Customization & Extensibility)

### 1. 监听三大运维领域事件与告警联动
无需侵入框架源码，通过 Spring 原生 `@EventListener` 即可优雅监听端点阻断、探针降级与线程池饱和事件：
```java
@Component
@Slf4j
public class ObservabilityAlertListener {

    @EventListener
    public void onThreadPoolRejected(ThreadPoolRejectedEvent event) {
        log.error("【报警】受管线程池饱和拒绝！池名: {}, 累计拒绝数: {}, 活跃线程: {}/{}, 队列堆积: {}",
                event.getPoolName(), event.getCumulativeRejectedCount(), event.getActiveCount(),
                event.getMaxPoolSize(), event.getQueueSize());
        // 推送钉钉/企业微信机器人或 Prometheus Alertmanager
    }

    @EventListener
    public void onHealthProbeDegraded(HealthProbeDegradedEvent event) {
        log.warn("【告警】组件健康探针降级！探针: {}, 组件: {}, 状态: {}, 耗时: {}ms, 原因: {}",
                event.getIndicatorClass(), event.getComponentName(), event.getStatus(),
                event.getCostMs(), event.getReason());
    }

    @EventListener
    public void onAccessDenied(ActuatorAccessDeniedEvent event) {
        log.warn("【安全审计】Actuator 端点未授权访问阻断！URI: {}, 来源 IP: {}, 原因: {}",
                event.getUri(), event.getClientIp(), event.getReason());
    }
}
```

### 2. 自定义外设安全防假死探针
继承 `AbstractSafeHealthIndicator` 即可轻松实现带超时强切断的自定义硬件或微服务网关探针：
```java
@Component
public class PaymentGatewayHealthIndicator extends AbstractSafeHealthIndicator {

    private final PaymentClient paymentClient;

    public PaymentGatewayHealthIndicator(PaymentClient paymentClient, ObservabilityProperties properties) {
        super(properties);
        this.paymentClient = paymentClient;
    }

    @Override
    protected String getComponentName() {
        return "paymentGateway";
    }

    @Override
    protected void doCheck(Health.Builder builder) throws Exception {
        // 执行物理网络连通探测（自动受控于配置的超时时长，超时自动降级）
        boolean reachable = paymentClient.ping();
        if (reachable) {
            builder.up().withDetail("gateway", "Alipay").withDetail("latency", "15ms");
        } else {
            builder.down().withDetail("gateway", "Alipay").withDetail("error", "支付网关无法连通");
        }
    }
}
```

---

## 4. 维度三：核心生产边界与非功能语义 (Production Boundary & Failure Modes)

1. **Kubernetes Liveness 黄金硬隔离与防死亡螺旋**：
   - `ObservabilityEnvironmentPostProcessor` 默认注入 `management.endpoint.health.group.liveness.include=livenessState,ping`；
   - 存活探针（`/actuator/health/liveness`）坚决排除数据库、Redis、Kafka、Storage 等外部组件；即使外部网络全崩，容器依然存活，避免因重启加剧整个拓扑雪崩；
   - 就绪探针（`/actuator/health/readiness`）全量聚合外部关键组件，当探测失败时优雅摘除流量，实现无损隔离。
2. **探针假死硬切断与状态机映射**：
   - 探测逻辑耗时超出 `ateng.boot.observability.health.timeout`（默认 `2s`）时，框架立即触发 `future.cancel(true)` 强行切断底层调用，直接向 Actuator 返回 `Status.UNKNOWN`（携带 `{"status":"TIMEOUT","timeout":"PT2S","reason":"Probe execution timed out"}`）；
   - 坚决杜绝因网络挂起拖死 Actuator 处理线程，保障探针高可用。
3. **CIDR 白名单免密与凭据互斥核验**：
   - 请求到达高危端点（`/actuator/env`、`/actuator/beans` 等）时，优先匹配 `allowed-ip-ranges`（如 `10.0.0.0/8`）；
   - 命中白名单直接 200 放行，便于机房内网巡检；未命中白名单时执行 HTTP Basic Auth 与 `X-Actuator-Token` 凭据比对（OR 逻辑）；
   - 比对采用 `MessageDigest.isEqual` 恒定时间防时序攻击，未授权直接阻断 403。
4. **线程池饱和追踪与防 NPE 防线**：
   - `TrackingRejectedExecutionHandler` 在包装底层策略时，严格校验非空与防递归包裹；
   - 任务拒绝时先原子累加 Prometheus 计数器，再广播 `ThreadPoolRejectedEvent`，最后执行原始策略，保障调用因果链绝对完备。

---

## 5. 维度四：物理排版与代码全真验证 (Evergreen & Zero-Glitch)

本模块全部功能特性已在独立 1:1 场景沙箱工程 [`ateng-boot-sample-observability`](../ateng-boot-samples/ateng-boot-sample-observability) 中提供真实自洽的端到端可运行用例，并在本地回归验证全绿：
- 场景源码：[`ObservabilityDemoController.java`](../ateng-boot-samples/ateng-boot-sample-observability/src/main/java/io/github/atengk/boot/sample/observability/controller/ObservabilityDemoController.java)
- 端到端全景测试：[`ObservabilityScenarioTest.java`](../ateng-boot-samples/ateng-boot-sample-observability/src/test/java/io/github/atengk/boot/sample/observability/ObservabilityScenarioTest.java)
  - 场景一：Prometheus 文本指标端点抓取与受管线程池动态打标验证
  - 场景二：编程式与声明式（`@ObservedMetric`）业务指标打点与分位数抓取断言
  - 场景三：`/actuator/health` 聚合探针与 K8s 存活/就绪探针黄金硬隔离断言（Liveness 排除外部依赖）
  - 场景四：业务正常 API 放行不受 Actuator 安全过滤器干扰
  - 场景五：白名单端点（`health`、`info`、`prometheus`）免密无 Token 畅行无阻
  - 场景六：CIDR IP 白名单免密穿透与敏感高危端点（`/actuator/env`）直接放行
  - 场景七：非白名单 IP 下 HTTP Basic Auth 认证访问与凭据防卫
  - 场景八：高危敏感端点通过 `X-Actuator-Token` 凭据防卫与鉴权放行
  - 场景九：线程池饱和拒绝发布 `ThreadPoolRejectedEvent` 领域事件断言
  - 场景十：安全探针防假死超时降级与发布 `HealthProbeDegradedEvent` 领域事件断言
  - 场景十一：Redis 等安全健康探针弱装配与离线自洽探测断言
- 本地构建与场景准出验收命令：
  ```bash
  mvn test -pl ateng-boot-observability,ateng-boot-samples/ateng-boot-sample-observability -am -T 1C -nsu -B
  ```
