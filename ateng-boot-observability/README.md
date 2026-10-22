# ateng-boot-observability 云原生可观测性与端点安全治理 Starter

> 基于 Spring Boot 3 Actuator、Micrometer Prometheus 与 JDK 17 原生并发能力构建的工业级云原生可观测性、健康探针防假死与端点安全治理底座。提供受管线程池动态度量与任务拒绝追踪绑定器（`AtengThreadPoolMeterBinder`）、异步防假死受管健康探针基类（`AbstractSafeHealthIndicator`）、四大专属领域弱探测探针体系、高优先级端点生产安全加固过滤器（`ActuatorEndpointSecurityFilter`）、Kubernetes 存活/就绪探针解耦治理与 100% 内存自洽自验沙箱。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.observability.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **度量指标绑定** | 受管线程池全量自动发现与动态打标绑定器 | `AtengThreadPoolMeterBinder` | `metrics.prometheus.enabled`<br>`metrics.thread-pool.enabled` | `bindTo(MeterRegistry registry)`：容器启动时自动扫描全量 `ThreadPoolTaskExecutor` 与 `ThreadPoolExecutor`，打标 `pool="<beanName>"` 并向 Prometheus 导出核心、最大、活跃、队列及拒绝指标 | `AtengThreadPoolMeterBinderTest` / `ObservabilityScenarioTest` |
| **任务拒绝追踪** | 线程池任务拒绝原子累加追踪装饰器 | `TrackingRejectedExecutionHandler` | - | `rejectedExecution(Runnable r, ThreadPoolExecutor executor)`<br>`getCumulativeRejectedCount()`<br>`getDelegate()`：装饰器透明包装原生策略，原子累加并在 Prometheus 暴露为 `ateng_threadpool_rejected_tasks_total` 计数器 | `TrackingRejectedExecutionHandlerTest` |
| **探针防假死基类** | 生产级受管健康探针超时硬切断抽象基类 | `AbstractSafeHealthIndicator` | `health.timeout`（默认 `2s`） | `health()`<br>`doCheck(Health.Builder builder)`<br>`getTimeout()`<br>`getExecutor()`：将子探针隔离在专属守护线程池中异步探测，超时立即硬切断降级返回 `Status.UNKNOWN`（带 `status=TIMEOUT`），异常返回 `Status.DOWN`，杜绝假死拖垮 Actuator | `AbstractSafeHealthIndicatorTest` |
| **存储专属探针** | 对象存储本地目录与 S3 连通性探针 | `StorageHealthIndicator` | `health.storage.enabled` | 弱探测 `StorageDriverFactory` 与 `StorageDriver`，校验本地目录存在、是否为目录与写入权限（`Files.isWritable`）或 S3 连通性，自动执行凭证正则掩码脱敏 | `StorageHealthIndicatorTest` / `ObservabilityScenarioTest` |
| **消息专属探针** | RocketMQ 5.x 客户端与消费监听容器探针 | `RocketmqHealthIndicator` | `health.rocketmq.enabled` | 弱探测 `RocketmqTemplate` 的 `Producer` 实例及 `RocketmqConsumerContainer` 的消费运行状态与活跃端点数 | `RocketmqHealthIndicatorTest` |
| **通信专属探针** | 实时通信 WebSocket/SSE 双通道会话探针 | `RealtimeHealthIndicator` | `health.realtime.enabled` | 弱探测 `RealtimeSessionManager` 活跃会话数、在线用户数及 `RealtimeClusterBroadcaster` 集群广播节点状态 | `RealtimeHealthIndicatorTest` |
| **数据源专属探针** | 动态多数据源与连接池连通性探针 | `DynamicDatasourceHealthIndicator` | `health.dynamic-datasource.enabled` | 探查容器内全部数据源连接可用性（`isValid(1)`），联动 `DynamicDatasourceGuard` 诊断多数据源与 `DynamicRoutingDataSource` 状态 | `DynamicDatasourceHealthIndicatorTest` / `ObservabilityScenarioTest` |
| **端点安全守卫** | Actuator 端点生产加固与高优先级过滤器 | `ActuatorEndpointSecurityFilter` | `security.lockdown-enabled`<br>`security.token` | `doFilterInternal(...)`：优先级 `HIGHEST_PRECEDENCE + 20`，白名单（`health`、`info`、`prometheus`、`pre-stop`）免密放行；高危敏感端点（`env`、`beans` 等）强制核验 `X-Actuator-Token`，恒定时间比对防时序攻击，未授权阻断 403 | `ActuatorEndpointSecurityFilterTest` / `ObservabilityScenarioTest` |
| **云原生基线后置** | 生产安全基线环境后处理器 | `ObservabilityEnvironmentPostProcessor` | `ateng.boot.observability.enabled` | `postProcessEnvironment(...)`：启动早期最低优先级注入默认安全暴露端点（`health,info,prometheus`）与 Kubernetes 探针自动开启配置，用户声明优先 | `ObservabilityEnvironmentPostProcessorTest` |
| **强类型配置属性** | 全局强类型配置属性类与 IDE 补全元数据 | `ObservabilityProperties` | `ateng.boot.observability.*` | 包含 `enabled`、`metrics.prometheus.enabled`、`metrics.thread-pool.enabled`、`health.timeout`（`Duration`）、`health.*.enabled`、`security.lockdown-enabled`、`security.token` | `ObservabilityPropertiesTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-observability` 是面向云原生容器编排与 Kubernetes 生产集群的专有可观测性 Starter：
- **受管线程池全透明度量**：自动识别容器内所有异步线程池并以 Bean 标识打标，彻底终结线上多线程运行黑盒；
- **健康探针防假死硬切断**：采用受管异步隔离与强类型超时断开（默认 2s），彻底消除下游网络抖动或中间件假死引发的 Actuator 线程池耗尽与 K8s 存活检查误杀雪崩；
- **端点生产加固与防配置泄露**：白名单免密与高危敏感端点加固隔离，恒定时间 Token 比对与双斜杠混淆防卫，从根本上杜绝 `/actuator/env`、`/actuator/beans` 的凭证与元数据泄露；
- **弱反射条件探测装配**：与各业务 Starter 保持纯净弱解耦，仅在类路径检测到对应组件时自动装配探针，零强依赖；
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
原生 Spring Boot Actuator 的健康检查在下游中间件发生网络挂起时会同步阻塞，导致 Actuator 独占工作线程池迅速耗尽并引发 Kubernetes Liveness 存活探针失败误杀容器；敏感端点（如 `/actuator/env`）一旦意外暴露极易泄露数据库凭据；各业务线程池运行状态缺乏自动度量导出。

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
      security:
        lockdown-enabled: true
        token: "my-production-secret-token"
```

### 开箱极简代码示例

定义任意 Spring 受管线程池，模块会自动完成动态扫描、打标与任务拒绝追踪：
```java
@Configuration
public class TaskPoolConfiguration {

    @Bean(name = "orderAsyncExecutor")
    public ThreadPoolTaskExecutor orderAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("order-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
```

访问 `/actuator/prometheus` 即可直接抓取到打标指标：
```text
# HELP ateng_threadpool_core_size The core number of threads
# TYPE ateng_threadpool_core_size gauge
ateng_threadpool_core_size{pool="orderAsyncExecutor",type="platform"} 10.0

# HELP ateng_threadpool_active_count The approximate number of threads that are actively executing tasks
# TYPE ateng_threadpool_active_count gauge
ateng_threadpool_active_count{pool="orderAsyncExecutor",type="platform"} 0.0

# HELP ateng_threadpool_rejected_tasks_total The total number of tasks rejected by the executor
# TYPE ateng_threadpool_rejected_tasks_total counter
ateng_threadpool_rejected_tasks_total{pool="orderAsyncExecutor",type="platform"} 0.0
```

---

## 3. 维度二：生产扩展接管 (Customization & Extensibility)

当开发者需要为自定义物理外设、第三方外部网关或企业专有系统编写健康探针时，只需继承 `AbstractSafeHealthIndicator` 即可无缝获得全套异步超时切断与状态降级能力：

### 自定义外设安全探针示例
```java
@Component
public class PaymentGatewayHealthIndicator extends AbstractSafeHealthIndicator {

    private final PaymentClient paymentClient;

    public PaymentGatewayHealthIndicator(PaymentClient paymentClient, ObservabilityProperties properties) {
        super(properties);
        this.paymentClient = paymentClient;
    }

    @Override
    protected void doCheck(Health.Builder builder) throws Exception {
        // 执行物理网络连通探测（自动受控于 2s 超时切断保护）
        boolean reachable = paymentClient.ping();
        if (reachable) {
            builder.up()
                   .withDetail("gateway", "Alipay")
                   .withDetail("latency", "12ms");
        } else {
            builder.down()
                   .withDetail("gateway", "Alipay")
                   .withDetail("error", "网络无法连通");
        }
    }
}
```

### 覆盖探针异步执行线程池
如果需要定制专职探测线程池参数，可通过声明同名 `@Bean` 轻松覆盖默认装配：
```java
@Bean(name = AtengHealthAutoConfiguration.HEALTH_PROBE_EXECUTOR_BEAN_NAME, destroyMethod = "shutdown")
public ExecutorService customHealthProbeExecutor() {
    return new ThreadPoolExecutor(
            4, 20, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new NamedThreadFactory("custom-health-probe", true),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );
}
```

---

## 4. 维度三：核心生产边界与非功能语义 (Production Boundary & Failure Modes)

1. **探针假死硬切断与状态机映射**：
   - 探测逻辑耗时超出 `ateng.boot.observability.health.timeout`（默认 `2s`）时，框架立即触发 `future.cancel(true)` 强行切断底层调用，直接向 Actuator 返回 `Status.UNKNOWN`（携带 `{"status":"TIMEOUT","timeout":"PT2S","reason":"Probe execution timed out"}`）；
   - 坚决杜绝因网络挂起拖死 Actuator 处理线程，保障 Kubernetes 存活探针（`/actuator/health/liveness`）持续保持响应，避免应用被编排引擎误杀。
2. **时序侧信道防卫 (Anti-Timing Attack)**：
   - `ActuatorEndpointSecurityFilter` 核验 `X-Actuator-Token` 请求头时，强制使用 `MessageDigest.isEqual` 恒定时间字节数组比对，杜绝攻击者通过逐字符响应耗时分析猜测内部管理密钥。
3. **敏感凭据脱敏防泄露**：
   - 探针报错信息在被序列化为 JSON 输出至 Actuator 响应前，自动通过内置正则规则（`(?i)(accessKey|secretKey|password|token|secret)[=:][^,&\\s]+`）执行敏感凭据掩码过滤（替换为 `******`），防止存储桶或数据库密码打印在健康检查页面中。
4. **最低优先级配置注入与用户覆写保障**：
   - `ObservabilityEnvironmentPostProcessor` 采用 `environment.getPropertySources().addLast(...)` 将基线配置挂载至最低优先级，用户在 `application.yml`、环境变量或配置中心声明的任何参数均享有 100% 绝对优先覆盖权。

---

## 5. 维度四：物理排版与代码全真验证 (Evergreen & Zero-Glitch)

本模块全部功能特性已在独立沙箱工程 [`ateng-boot-sample-observability`](../ateng-boot-samples/ateng-boot-sample-observability) 中提供真实自洽的端到端可运行用例，并在本地回归验证全绿：
- 场景用例：[`ObservabilityScenarioTest.java`](../ateng-boot-samples/ateng-boot-sample-observability/src/test/java/io/github/atengk/boot/sample/observability/ObservabilityScenarioTest.java)
- 执行测试验证命令：
  ```bash
  mvn test -pl ateng-boot-observability,ateng-boot-samples/ateng-boot-sample-observability -am -T 1C -nsu -B
  ```
