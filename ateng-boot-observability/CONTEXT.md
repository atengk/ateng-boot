# Observability Context

`ateng-boot-observability` 负责面向生产就绪的云原生可观测性、健康探针防假死与端点安全治理底座，提供 Prometheus 性能指标导出、全容器受管线程池动态度量、超时熔断保护健康探针、敏感端点安全守卫及 Kubernetes 探针智能解耦。

## Language

**AbstractSafeHealthIndicator**:
受管健康探针防假死抽象基类，基于独立受管线程执行实际探测，强制挂载强类型超时切断（默认 2s），在下游发生网络闪断或假死时快速熔断并返回 UNKNOWN/WARN 降级状态，避免阻塞 Actuator 线程池引发 K8s 存活检查雪崩。
_Avoid_: BaseHealthIndicator, TimeoutHealthIndicator, SafeProbe

**AtengThreadPoolMeterBinder**:
受管线程池全量自动发现与度量指标绑定器，自动扫描 Spring 容器内所有 ThreadPoolTaskExecutor 与 ThreadPoolExecutor，以 Bean 标识打标并向 Micrometer 注册 core/max/active/queue/rejected 等核心运行指标，兼容虚拟线程执行器。
_Avoid_: ThreadPoolMetrics, PoolMonitor, ThreadPoolCollector

**TrackingRejectedExecutionHandler**:
线程池任务拒绝追踪装饰器，采用装饰器模式透明包装原生 RejectedExecutionHandler，在触发拒绝策略时原子累加累计拒绝任务数并在 Prometheus 暴露为度量指标，随后委托给原始处理策略。
_Avoid_: CountingRejectedHandler, MetricsRejectedHandler, RejectedHandlerWrapper

**ActuatorEndpointSecurityFilter**:
部署于 Servlet 容器最前沿的高优先级安全守卫过滤器，对 Actuator 基础路径进行前置拦截，白名单端点（health、info、prometheus、pre-stop）免密放行，高危敏感端点（env、beans、heapdump 等）强制核验 X-Actuator-Token 凭据，未授权直接阻断 403/404。
_Avoid_: EndpointFilter, SecurityInterceptor, ActuatorAuthGuard

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

**ObservabilityEnvironmentPostProcessor**:
生产安全基线环境后处理器，在容器启动早期自动注入安全的默认暴露端点配置与 Kubernetes 探针自动开启配置。
_Avoid_: ObservabilityPostProcessor, ActuatorConfigProcessor
