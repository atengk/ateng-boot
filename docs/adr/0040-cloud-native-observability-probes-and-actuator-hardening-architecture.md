# 40. 云原生可观测性、健康探针防假死与端点安全治理架构决策 (Cloud-Native Observability, Fail-Safe Health Probes & Actuator Endpoint Hardening Architecture)

## 状态
`Accepted`

---

## 背景与痛点 (Context & Problem Statement)

在云原生微服务架构与 Kubernetes 容器编排环境中，应用系统的可观测性、健康检查探针与度量指标暴露已成为支撑系统高可用运行与弹性调度的核心基石。然而，在真实生产落地与高并发运维场景下，直接使用原生 Spring Boot Actuator 面临一系列深水区隐患与安全架构缺陷：

1. **高危敏感端点配置与元数据泄露风险 (Actuator Endpoint Exposure & Information Disclosure)**：
   Spring Boot 原生 Actuator 在缺少严格安全治理时，若暴露 `/actuator/env`、`/actuator/beans`、`/actuator/heapdump`、`/actuator/threaddump` 等敏感端点，外部攻击者可轻易探测环境变量（包含数据库账号、密码、密钥）及容器内部 Bean 依赖拓扑，引发严重的数据泄露与合规审查事故。
2. **下游物理故障引发探针假死与 Kubernetes 存活雪崩 (Probe Hanging & Kubernetes Death-Spiral)**：
   Spring Boot Actuator 原生 `HealthIndicator` 为同步阻塞调用，在探查下游外部物理资源（如数据库连接池、S3 存储桶、消息队列 Broker 等）时，若下游发生网络抖动、闪断或物理假死，探针调用将无限挂起。这不仅会迅速耗尽 Actuator 独占工作线程池，更会直接拖垮 Kubernetes 存活探针（Liveness Probe）导致容器被 K8s 集群误杀重启，引发大面积服务雪崩。
3. **受管线程池黑盒与任务拒绝监控缺位 (Thread Pool Blind-Spot & Silent Rejection)**：
   在异步任务密集型应用中，底层 `ThreadPoolTaskExecutor` 或 `ThreadPoolExecutor` 的核心线程数、活跃线程数、队列积压任务数及累计拒绝率，往往缺乏全局自动发现与统一度量打标机制，导致线程池打满与拒绝激增时运维人员无法从 Prometheus / Grafana 看板及时感知预警。
4. **探针实现碎片化与中间件强依赖污染 (Bloated Probes & Classpath Pollution)**：
   各中间件 Starter 通常各自编写私有探针，缺少标准基类防卫；且各探针若强依赖对应中间件 SDK 类，会导致非 Web 或非特定中间件消费者环境发生类加载异常或造成无谓的依赖膨胀。

---

## 决策内容与架构拓扑 (Decision & Architecture)

为彻底攻克上述生产隐患，`ateng-boot-observability` 确立了**“端点安全锁死与黄金探针解耦、超时硬切断防假死、全容器受管线程池动态打标、弱反射条件装配、零臃肿依赖与 100% 离线自洽”**的架构设计基准，核心拓扑如下：

```mermaid
flowchart TD
    subgraph ExternalRequests["外部访问与探针抓取流量"]
        K8sProbe["Kubernetes 探针<br>(/health/liveness, /health/readiness)"]
        PrometheusScraper["Prometheus 抓取器<br>(/actuator/prometheus)"]
        AdminRequest["运维管理员 / API 调用<br>(/actuator/env, /actuator/beans)"]
    end

    subgraph SecurityGate["最前沿生产安全守卫 (ActuatorEndpointSecurityFilter)"]
        Filter["ActuatorEndpointSecurityFilter<br>(Order = HIGHEST_PRECEDENCE + 20)"]
        WhiteListCheck{"白名单判定<br>(/health, /info, /prometheus, /pre-stop)"}
        LockdownCheck{"加固开关判定<br>(lockdown-enabled?)"}
        TokenVerify{"恒定时间比对<br>(MessageDigest.isEqual<br>X-Actuator-Token)"}
        ForbiddenResponse["HTTP 403 阻断拒绝<br>(Result.fail 统一响应)"]
    end

    subgraph HealthEngine["生产级防假死健康探针体系 (AbstractSafeHealthIndicator)"]
        HealthEntry["/actuator/health 聚合端点"]
        ProbeExecutor["atengHealthProbeExecutor<br>(受管守护线程池)"]
        TimeoutGate{"异步执行 & 超时切断<br>(默认 2s 阈值)"}
        TimeoutFallback["快速熔断降级<br>(Status.UNKNOWN + TIMEOUT)"]
        DomainStorage["StorageHealthIndicator<br>(目录可写 Files.isWritable & S3)"]
        DomainRocketMQ["RocketmqHealthIndicator<br>(Producer 实例 & 消费者容器)"]
        DomainRealtime["RealtimeHealthIndicator<br>(WS/SSE 在线会话 & 集群广播)"]
        DomainDynamicDS["DynamicDatasourceHealthIndicator<br>(多数据源连接池连通性)"]
    end

    subgraph MetricsEngine["受管线程池动态度量与拒绝追踪"]
        Binder["AtengThreadPoolMeterBinder<br>(自动扫描 Spring 容器受管线程池)"]
        Tracker["TrackingRejectedExecutionHandler<br>(装饰器模式原子累加拒绝数)"]
        PrometheusRegistry["Micrometer PrometheusMeterRegistry"]
    end

    K8sProbe --> Filter
    PrometheusScraper --> Filter
    AdminRequest --> Filter

    Filter --> WhiteListCheck
    WhiteListCheck -->|命中白名单| HealthEntry
    WhiteListCheck -->|命中白名单| PrometheusRegistry
    WhiteListCheck -->|未命中白名单| LockdownCheck

    LockdownCheck -->|未开启加固| Endpoints["原生敏感端点 (/env, /beans)"]
    LockdownCheck -->|开启加固| TokenVerify
    TokenVerify -->|Token 无效或缺失| ForbiddenResponse
    TokenVerify -->|Token 核验通过| Endpoints

    HealthEntry --> DomainStorage
    HealthEntry --> DomainRocketMQ
    HealthEntry --> DomainRealtime
    HealthEntry --> DomainDynamicDS

    DomainStorage --> TimeoutGate
    DomainRocketMQ --> TimeoutGate
    DomainRealtime --> TimeoutGate
    DomainDynamicDS --> TimeoutGate

    TimeoutGate -->|正常完成| HealthSuccess["Status.UP"]
    TimeoutGate -->|超时 (>2s)| TimeoutFallback
    TimeoutGate -->|不可逆异常| HealthDown["Status.DOWN (附带因果原因)"]

    Binder --> Tracker
    Binder --> PrometheusRegistry
```

---

### 1. 生产级受管健康探针防假死抽象基类 (`AbstractSafeHealthIndicator`)
1. **异步执行隔离**：将各业务中间件子探针的具体探测逻辑（`doCheck`）委托给专属受管异步守护线程池 `atengHealthProbeExecutor` 执行，严禁直接在 Actuator 派发线程中执行物理网络 IO；
2. **强类型超时硬切断**：探测操作统一受控于强类型超时配置 `ateng.boot.observability.health.timeout`（默认 `2s`）。当探测超时立即触发 `future.cancel(true)` 硬切断，快速返回 `Health.unknown()` 降级状态（附带 `status=TIMEOUT`、`timeout` 阈值与原因）；
3. **因果异常传递**：探测发生异常时转译返回 `Health.down(ex)`，保留原始异常因果链与错误消息，坚决杜绝异常吞咽。

### 2. 四大 Starter 专属弱探测探针体系 (Domain-Specific Indicators)
1. **`StorageHealthIndicator`**：面向 `ateng-boot-storage`，探测 Local 驱动基准目录的物理存在性、目录属性与写入权限（`Files.isWritable`），或 S3 驱动的 Bucket 连通性，自动执行敏感密钥与 Token 的正则掩码脱敏；
2. **`RocketmqHealthIndicator`**：面向 `ateng-boot-rocketmq`，探查 5.x gRPC `Producer` 实例有效性与 `RocketmqConsumerContainer` 消费监听容器的运行状态及活跃消费端点数；
3. **`RealtimeHealthIndicator`**：面向 `ateng-boot-realtime`，无锁采集当前活跃 WebSocket 与 SSE 双通道会话数、在线用户总数及 Redis Pub/Sub 集群广播状态；
4. **`DynamicDatasourceHealthIndicator`**：探查 Spring 容器中全部数据源连接可用性（`isValid(1)`），并联动 `DynamicDatasourceGuard` 诊断多数据源环境与动态路由数据源（`DynamicRoutingDataSource`）健康度。

### 3. 全容器受管线程池动态打标与拒绝追踪 (`AtengThreadPoolMeterBinder`)
1. **全容器自动化扫描**：容器启动后通过 `SmartInitializingSingleton` 自动扫描所有 `ThreadPoolTaskExecutor` 与 `ThreadPoolExecutor` Bean；
2. **统一命名空间与 Tag 打标**：向 Micrometer 注册统一前缀指标（`ateng_threadpool_core_size`、`ateng_threadpool_max_size`、`ateng_threadpool_active_size`、`ateng_threadpool_queue_size`），以 `pool="<beanName>"` 进行统一维度切分；
3. **拒绝策略透明追踪**：通过装饰器模式透明包装原生 `RejectedExecutionHandler` 为 `TrackingRejectedExecutionHandler`，原子累加拒绝任务数并暴露为 `ateng_threadpool_rejected_total` 计数器指标。

### 4. 高优先级端点安全守卫与 K8s 黄金探针解耦治理 (`ActuatorEndpointSecurityFilter`)
1. **动静分流与白名单免密**：对 Kubernetes 存活与就绪探针（`/actuator/health`、`/actuator/health/liveness`、`/actuator/health/readiness`）、应用元数据（`/actuator/info`）及指标导出（`/actuator/prometheus`）免密放行；
2. **敏感端点恒定时间鉴权防卫**：当开启生产加固（`lockdown-enabled=true`）时，对 `/actuator/env`、`/actuator/beans` 等端点强制核验请求头 `X-Actuator-Token`；采用 `MessageDigest.isEqual` 恒定时间比对，未授权时阻断返回 HTTP 403 JSON，彻底杜绝配置泄露；
3. **路径混淆清洗防卫**：内置规范化清洗多重斜杠（如 `/actuator//env`），消除反向代理或网关路径混淆绕过隐患。

### 5. 生产安全基线环境后处理器 (`ObservabilityEnvironmentPostProcessor`)
1. 在容器初始化早期向环境注入最低优先级（`addLast`）的安全基线配置，自动收敛默认暴露端点为 `health,info,prometheus` 并开启 K8s 探针感知；
2. 开发者在 `application.yml` 中声明的任何配置均可无缝覆写，保障开箱即用与生产可定制性的完美平衡。

---

## 影响与收益 (Consequences)

- **正向收益 (Pros)**：
  1. 彻底解决高危敏感端点暴露造成的机密信息泄露隐患，构建企业级生产安全防护底线；
  2. 探针超时切断与异步隔离彻底杜绝物理中间件假死引发的 Actuator 线程池耗尽与 K8s 存活探针误杀雪崩；
  3. 受管线程池全量度量与任务拒绝透明追踪，开箱即用无缝接入 Prometheus / Grafana 看板监控；
  4. 采用 `@ConditionalOnClass` 静态内部配置类弱装配，零强制依赖其他 Starter，模块体积轻量纯净。
- **负向影响 (Cons)**：
  1. 访问加固后的敏感端点（如 `/actuator/env`）时，管理员必须显式携带 `X-Actuator-Token` 请求头；
  2. 探测超时默认设定为 2 秒，在极端慢网络环境下子探针可能返回 UNKNOWN 降级状态（可通过 `ateng.boot.observability.health.timeout` 调整）。
