# Web Context

`ateng-boot-web` 负责 Web 运行时的非侵入性增强，涵盖请求链路追踪流转、全局异常安全拦截、JSON 序列化标准与跨域策略。

## Language

**TraceFilter**:
在 HTTP 请求入口处提取或生成链路追踪标识、写入日志 MDC、回写响应头并在出口安全清理的高优先级 Web 过滤器。
_Avoid_: LogFilter, TraceInterceptor, MDCFilter

**GlobalExceptionHandler**:
捕获控制器抛出的各类已知业务异常与未知系统异常，将其安全转译为统一 Result 结构并对内部敏感错误做生产脱敏的切面处理器。
_Avoid_: ExceptionAdvice, ErrorHandler, WebExceptionHandler

**JacksonCustomizer**:
基于 Spring 标准构建器定制器挂载的长整型转字符串（防雪花失真）与 Java 8 全量日期东八区标准化配置。
_Avoid_: ObjectMapperConfig, JsonConfig, JacksonConfig

**CorsConfiguration**:
基于属性开关动态启闭与配置允许跨域源、HTTP 方法及请求头的 Web 安全规则。
_Avoid_: CrossFilter, WebCorsConfig

**AtengAsyncAutoConfiguration**:
上下文感知受管异步线程池自动装配类（已下沉收敛至 `ateng-boot-core` 统一底座），web 模块直接依赖继承，为 Spring 异步体系统一提供四维上下文快照（MDC、用户、租户、忽略租户）调度与未捕获异常处理。
_Avoid_: AsyncConfig, ThreadPoolConfiguration, AsyncThreadPoolAutoConfiguration

**AsyncProperties**:
异步线程池运行时增强配置属性（位于 `ateng-boot-core`），以 ateng.boot.async 为前缀，提供基于 CPU 核心数自适应的线程池容量、队列缓冲与优雅停机超时控制。
_Avoid_: ThreadPoolProperties, AsyncConfigProperties

**IpUtil**:
多级反向代理客户端真实 IP 提取工具，按序解析 X-Forwarded-For 首个有效 IP，并安全兼容本地环回与 IPv6 规整。
_Avoid_: AddressUtil, NetworkUtil, ClientIpUtil

**ServletUtil**:
Servlet 上下文与请求响应静态操作门面，基于 RequestContextHolder 安全获取当前 Request/Response 并提供 Header、Cookie、JSON/AJAX 请求识别、JSON 文本直接写入，以及基于 RFC 6266 / RFC 5987 双重文件名防乱码编码的安全流式文件下载与导出辅助（setDownloadHeader / writeDownload）。
_Avoid_: WebUtil, HttpUtil, RequestUtil

**WebLog**:
Web 接口审计日志注解，支持标注于 Controller 类或方法上，提供操作描述、入参/出参打印控制、耗时观测与敏感参数显式排除配置。
_Avoid_: ApiLog, AuditLog, RequestLog, LogOperation

**WebLogAspect**:
基于 AOP 环绕拦截的 Web 接口审计日志切面，由 `ateng.boot.web.log.enabled` 开关驱动装配，自动提取客户端真实 IP、感知链路追踪 traceId、过滤大文件与流式对象防 OOM，并在方法成功或异常时统一输出结构化审计日志。
_Avoid_: ApiLogAspect, AuditLogAspect, RequestLogAspect

**Idempotent**:
Web 接口防重幂等注解，声明于 Controller 方法上，定义幂等键 SpEL 表达式、锁定超时时长（TTL）与异常自动释放策略。
_Avoid_: RepeatSubmit, PreventDuplicate, Idempotence, NoRepeat

**IdempotentAspect**:
基于 AOP 环绕拦截的接口防重幂等切面，解析 `@Idempotent` 契约，调用 `IdempotentKeyStore` 实施原子加锁与全生命周期治理。
_Avoid_: IdempotentInterceptor, RepeatSubmitAspect, DuplicateAspect

**IdempotentKeyStore**:
幂等键状态存储与锁操作的通用 SPI 接口，定义原子加锁（tryLock）、主动释放（unlock）与过期时间淘汰契约。
_Avoid_: LockStore, IdempotentManager, KeyStorage, DistributedLockStore

**InMemoryIdempotentKeyStore**:
基于 JDK 原生无锁并发容器与防爆淘汰机制的单机内存幂等存储默认实现，提供零外部依赖的开箱即用保障。
_Avoid_: LocalKeyStore, MemoryLockStore, SimpleKeyStore

**RedisIdempotentKeyStore**:
基于 Spring Data Redis 的分布式原子加锁与过期释放实现，作为 Tier 2 次级分布式存储提供者。
_Avoid_: DistributedRedisStore, RedisLockStore

**RedissonIdempotentKeyStore**:
基于 RedissonClient RBucket 原生原子抢占与存活周期的分布式幂等存储实现，作为 Tier 1 最高优先级分布式存储提供者。
_Avoid_: RedissonLockStore, RedissonStore

**RedissonIdempotentAutoConfiguration**:
Tier 1 幂等存储自动装配类，当容器中存在 RedissonClient Bean 时自动装配 RedissonIdempotentKeyStore。
_Avoid_: RedissonAutoConfig, RedissonStoreConfiguration

**RedisIdempotentAutoConfiguration**:
Tier 2 幂等存储自动装配类，当容器中存在 StringRedisTemplate Bean 且未命中 Redisson 时自动装配 RedisIdempotentKeyStore。
_Avoid_: RedisAutoConfig, RedisStoreConfiguration

**AtengIdempotentAutoConfiguration**:
Tier 3 核心自动装配类，在分布式中间件之后执行，负责单机内存存储兜底（InMemoryIdempotentKeyStore）与 AOP 切面（IdempotentAspect）装配。
_Avoid_: IdempotentConfig, IdempotentAspectAutoConfiguration

**RateLimiter**:
Web 接口防刷限流注解，声明于 Controller 方法上，定义时间窗口、请求次数阈值、限流维度与 SpEL 业务键。
_Avoid_: Limit, RateLimit, TrafficControl, FlowControl

**RateLimiterAspect**:
基于 AOP 环绕拦截的接口限流切面，解析 `@RateLimiter` 契约，调用 `RateLimiterStore` 执行原子令牌判定并管理超限熔断。
_Avoid_: LimitAspect, RateLimitInterceptor, FlowAspect

**RateLimiterStore**:
限流状态统计与许可扣减的通用 SPI 接口，定义原子尝试获取许可（tryAcquire）与状态清理契约。
_Avoid_: LimitStore, RateLimiterManager, RateLimitStorage

**CorsOriginProvider**:
动态跨域来源校验与提供者 SPI 接口，定义根据传入 Origin 动态判定是否允许跨域访问的契约。
_Avoid_: OriginValidator, CorsProvider, AllowedOriginProvider

**ApiSign**:
Web 接口数据签名与防篡改注解，声明于 Controller 方法或类上，提供双向或单向验签与加签控制及防重放时效配置。
_Avoid_: Sign, DataSign, Signature, SecureApi

**ApiSigner**:
数字签名生成与验证的通用 SPI 接口，定义明文摘要哈希计算与数字验签契约。
_Avoid_: SignManager, SignatureEngine, CryptoSigner

**Traffic Drain**:
容器下线前将就绪探针置为拒绝流量并执行静默等待的流量排空阶段，防止在途请求与网关路由摘除延迟导致 502/504。
_Avoid_: PreStop Sleep, Force Kill, Fast Shutdown

**Slow Request Alert**:
单次 HTTP 请求执行耗时超过预设阈值时触发的主动性能告警与全链路溯源现场记录机制。
_Avoid_: Request Timeout, Slow Query, Latency Monitor


