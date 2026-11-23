# Observability Context

`ateng-boot-observability` 负责面向生产就绪的云原生可观测性、健康探针防假死与端点安全治理底座，提供 Prometheus 性能指标导出、全容器受管线程池动态度量、超时熔断保护健康探针、敏感端点安全守卫及 Kubernetes 探针智能解耦。

## Language

**AbstractSafeHealthIndicator**:
受管健康探针防假死抽象基类，基于独立受管线程执行实际探测，强制挂载强类型超时切断（默认 2s），在下游发生网络闪断或假死时快速熔断并返回 UNKNOWN/DOWN 降级状态，避免阻塞 Actuator 线程池引发 K8s 存活检查雪崩。
_Avoid_: BaseHealthIndicator, TimeoutHealthIndicator, SafeProbe

**AtengThreadPoolMeterBinder**:
受管线程池全量自动发现与度量指标绑定器，自动扫描 Spring 容器内所有 ThreadPoolTaskExecutor 与 ThreadPoolExecutor，以 Bean 标识打标并向 Micrometer 注册 core/max/active/queue/rejected 等核心运行指标，兼容虚拟线程执行器。
_Avoid_: ThreadPoolMetrics, PoolMonitor, ThreadPoolCollector

**TrackingRejectedExecutionHandler**:
线程池任务拒绝追踪装饰器，采用装饰器模式透明包装原生 RejectedExecutionHandler，在触发拒绝策略时原子累加累计拒绝任务数并在 Prometheus 暴露为度量指标，随后委托给原始处理策略。
_Avoid_: CountingRejectedHandler, MetricsRejectedHandler, RejectedHandlerWrapper

**ActuatorEndpointSecurityFilter**:
部署于 Servlet 容器最前沿的高优先级安全守卫过滤器，对 Actuator 基础路径进行前置拦截，白名单端点（health、info、prometheus、pre-stop）免密放行，高危敏感端点（env、beans、heapdump 等）基于 Core `CidrMatcher` 与 X-Actuator-Token 凭据核验放行，未授权直接阻断 403/404。
_Avoid_: EndpointFilter, SecurityInterceptor, ActuatorAuthGuard, observability.security.CidrMatcher

**StorageHealthIndicator**:
面向 ateng-boot-storage 的专有对象存储探针，弱依赖探测 Local 模式本地目录可写状态与 S3 模式客户端连通性。
_Avoid_: FileHealthIndicator, OssHealthIndicator

**RocketmqHealthIndicator**:
面向 ateng-boot-rocketmq 5.x gRPC 瘦客户端的专有消息引擎连通性探针，探测 Producer / PushConsumer 客户端实例与底层 Channel 运行状态。
_Avoid_: RocketHealthIndicator, MqHealthIndicator

**RealtimeHealthIndicator**:
面向 ateng-boot-realtime 的实时通信探针，采集当前活跃 WebSocket 会话数、SSE 连接数与 Redis Pub/Sub 广播存活状态。
_Avoid_: WsHealthIndicator, SseHealthIndicator

**DynamicDatasourceHealthIndicator**:
面向多数据源环境的轻量连通性探针，周期性探测各数据源连接池健康度。
_Avoid_: MultiDataSourceHealthIndicator, DynamicDbProbe

**RedisSafeHealthIndicator**:
面向 ateng-boot-redis 的防假死安全健康探针，在独立受管守护线程池中执行 Redis PING 与连接池活性探测，超时硬切断返回 UNKNOWN/TIMEOUT 降级状态，透明替换原生同步阻塞探针。
_Avoid_: SafeRedisProbe, RedisCheck, RedisTimeoutHealthIndicator

**KafkaSafeHealthIndicator**:
面向 ateng-boot-kafka 的防假死安全健康探针，基于受管异步线程探测 Kafka 客户端集群连通性与元数据，超时硬切断避免挂起。
_Avoid_: SafeKafkaProbe, KafkaHealth, KafkaProbe

**RabbitmqSafeHealthIndicator**:
面向 ateng-boot-rabbitmq 的防假死安全健康探针，基于受管异步线程探测 RabbitMQ ConnectionFactory 与 Channel 连通性，透明接管原生阻塞探针。
_Avoid_: RabbitHealth, SafeRabbitProbe, RabbitConnectionProbe

**SearchSafeHealthIndicator**:
面向 ateng-boot-search 的防假死安全健康探针，基于受管异步线程探测 Elasticsearch 集群 Ping 状态与节点连通性，防止网络抖动拖垮探针。
_Avoid_: EsHealthIndicator, ElasticCheck, SearchProbe

**MailSafeHealthIndicator**:
面向 ateng-boot-mail 的邮件通信防假死安全健康探针，仅探测 `MailAccountRegistry` 中静态预热账号列表的物理 SMTP 连通性（跳过动态租户缓存账号以避免单租户异常拖垮全局状态，静态账号为空时返回 `UNKNOWN`），内置默认 `60s` 防刷缓存 TTL 防范公网邮箱服务商限流，并对异常详情中的邮箱、密码与令牌自动脱敏掩码。
_Avoid_: MailHealthProbe, SmtpHealthIndicator, EmailHealthCheck

**SmsSafeHealthIndicator**:
面向 ateng-boot-sms 的短信渠道防假死安全健康探针，仅检视 `SmsChannelRegistry` 静态注册渠道的凭据合法性、熔断状态与可选只读余额探查（`probe-balance`，绝不消耗计费短信，跳过动态租户缓存且空静态池返回 `UNKNOWN`），标配 `60s` 防刷缓存与异常凭据自动脱敏。
_Avoid_: SmsHealthProbe, SmsGatewayIndicator, SmsBalanceCheck

**MetricsTemplate**:
业务指标度量统一编程式操作门面，基于 Micrometer 封装 Counter、Timer、Gauge 与 DistributionSummary 的高频采集语义，提供流式计时、函数式测量与常用多维打标能力。
_Avoid_: MetricService, MetricHelper, PrometheusTemplate, MeterTemplate

**ObservedMetric**:
声明式方法级度量监控注解，声明于 Spring 受管组件方法或类上，通过 AOP 切面透明记录执行耗时分位数（P50/P95/P99）、直方图（Histogram）、SLO 耗时桶（SLA）及成功与失败调用计数并自动打标导出。
_Avoid_: Metrics, Monitor, Measure, TimedMetric

**ObservedMetricAspect**:
声明式方法度量 AOP 环绕切面，拦截 `@ObservedMetric` 注解方法，统一采集 Timer 耗时、分位数/SLO 直方图与调用状态计数器（`status=success/error`、`exception`），并在度量异常时静默降级以保障业务主流程零阻断。
_Avoid_: MetricInterceptor, TimedAspect, MonitorAdvice

**AtengMetricsRegistryCustomizer**:
全局度量注册表定制与高基数防爆治理器，基于 `MeterRegistryCustomizer` 与 `MeterFilter` 自动注入应用全局公共标签（`application` 与 `common-tags`）、截断超长标签值（`max-tag-value-length`）、限制单指标最大标签基数（`max-meter-cardinality`）并支持前缀黑名单过滤，防止 Prometheus TSDB 维度爆炸。
_Avoid_: MetricsFilter, TagLimiter, CardinalityGuard

**ThreadPoolRejectedEvent**:
受管线程池发生任务饱和拒绝时由 TrackingRejectedExecutionHandler 发布的 Spring 领域事件，携带拒绝线程池名称、当前活跃线程、队列深度及累计拒绝计数。
_Avoid_: RejectedTaskEvent, PoolRejectEvent, ThreadPoolOverflowEvent

**HealthProbeDegradedEvent**:
健康探针探测超时或抛出异常降级时发布的 Spring 领域事件，携带故障组件名称、耗时与因果异常，便于下游实现实时告警。
_Avoid_: ProbeFailureEvent, HealthDownEvent, ProbeTimeoutEvent

**HealthProbeRecoveredEvent**:
健康探针从故障或超时降级状态（`DOWN` / `TIMEOUT`）恢复为健康状态（`UP`）时发布的对称 Spring 领域事件，携带探针名称、组件名称、故障前状态、累计连续失败次数及故障持续时长（`downtimeMs`），与 `HealthProbeDegradedEvent` 构成故障告警与自愈消警闭环。
_Avoid_: ProbeUpEvent, HealthRestoredEvent, ProbeRecoveryNotification

**ActuatorAccessDeniedEvent**:
外部请求访问加固敏感端点未通过 Token、Basic Auth 或 IP 白名单核验被阻断时发布的 Spring 领域事件，便于审计未授权探测行为。
_Avoid_: EndpointBlockEvent, SecurityRejectEvent

**ObservabilityEnvironmentPostProcessor**:
生产安全基线环境后处理器，在容器启动早期自动注入安全的默认暴露端点配置与 Kubernetes 探针自动开启配置，并强制将外部物理依赖从 Liveness 存活检查剥离至 Readiness 就绪检查。
_Avoid_: ObservabilityPostProcessor, ActuatorConfigProcessor

**AdminClientSecretHttpRequestInterceptor**:
面向 Spring Boot Admin 客户端（SBA Client）的出站注册握手密钥拦截器，在实例向管控中心发起注册与心跳请求时透明注入 `X-Admin-Client-Secret` 请求头，与 `ateng-boot-admin` 服务端形成零配置安全准入闭环。
_Avoid_: SbaSecretInterceptor, AdminAuthInterceptor, RegistrationHeaderFilter

**AiSafeHealthIndicator**:
面向 `ateng-boot-ai` 的防假死安全健康探针，仅校检 `AiModelRegistry` 静态预热模型配置完整性及可选零计费只读 `/models` 端点探活（严禁触发扣费对话请求，未配密钥安全返回 `UNKNOWN`），内置默认 `60s` 防刷缓存与异常凭据自动脱敏。
_Avoid_: AiHealthProbe, LlmHealthIndicator, OpenAiHealthCheck
