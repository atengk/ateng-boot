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

**ContentCachingFilter**:
通用请求体可重读过滤器与包装管道，解决 ServletInputStream 阅后即焚冲突并在超限时快速失败截断防 OOM。
_Avoid_: BodyCacheFilter, ReReadFilter, RequestWrapperFilter

**OperateLog**:
声明式企业级操作审计注解，声明于 Controller 方法上，定义模块、操作类型、业务描述与敏感参数排除。
_Avoid_: OperationLog, SysLog, UserLog, ActionLog

**OperateLogAspect**:
基于 AOP 环绕拦截的结构化操作审计切面，自动关联四维全链路上下文并在出参联动视图脱敏（守卫 ADR-0012 内存真明文）。
_Avoid_: SysLogAspect, AuditAspect, ActionAspect

**OperateLogStorageProvider**:
操作审计存储解耦 SPI 接口，定义审计模型持久化与外部投递契约，支持自定义接入 ES/Kafka。
_Avoid_: AuditStore, LogStorage, OperateLogDao, AuditStorageProvider

**AtengGracefulShutdownHandler**:
云原生优雅停机排空生命周期处理器，协调就绪探针反转、静默等待、SmartLifecycle 兜底与原子幂等防重入。
_Avoid_: GracefulShutdownManager, PreStopHandler, DrainService

**SlowRequestFilter**:
长耗时慢请求性能主动告警过滤器，出口统计耗时、智能放行 WebSocket/SSE/下载长连接并输出单行全链路 WARN 告警。
_Avoid_: SlowFilter, LatencyFilter, RequestTimeoutFilter

**PayloadLoggingFilter**:
HTTP 请求与响应双向安全采样日志过滤器，内建 MIME 过滤、强类型截断、凭据正则脱敏与单行聚合输出。
_Avoid_: HttpDumpFilter, RequestResponseLogFilter, BodyPrintFilter

**SecurityHeadersFilter**:
向 HTTP 响应安全注入 OWASP 与等保三级推荐标准防护头（nosniff、SAMEORIGIN、xss-protection、referrer-policy）并抹除容器版本指纹的过滤器。
_Avoid_: SecurityFilter, HeaderFilter, SafeHeaderFilter

**XssFilter**:
XSS 跨站脚本安全清洗过滤器，协调请求包装器与 Jackson 反序列化器对 Query、Form 及 JSON 请求体实施双轨无害化清洗。
_Avoid_: AntiXssFilter, CleanFilter, ScriptFilter

**ApiVersion**:
声明式 API 多版本控制注解，配合 Spring MVC 原生 RequestCondition 挂载 URI 路径版本（/v{version}/）与请求头版本（X-API-Version）精准分流。
_Avoid_: VersionMapping, ApiV, VersionControl

**IpAccessControl**:
声明式 IP 访问控制注解，支持单 IP 与 IPv4/IPv6 CIDR 子网白名单/黑名单校验与快速拒绝。
_Avoid_: IpFilter, IpGuard, Firewall, ClientIpAuth

**Sm2Signer**:
基于国密 SM2 椭圆曲线数字签名算法与 SM3 杂凑摘要实现的 API 双向加签验签器，满足信创高合规场景。
_Avoid_: GmSigner, SmSigner, NationalCryptoSigner

**GlobalExceptionCustomizer**:
全局异常转译扩展点 SPI，允许业务方在标准 GlobalExceptionHandler 前置挂载特定领域异常的友好转译逻辑。
_Avoid_: ExceptionHandlerCustomizer, ErrorCustomizer, ExceptionSPI

**XssIgnore**:
XSS 清洗豁免注解，声明于 Controller DTO 实体属性或类上，指示 Jackson 反序列化器对特定富文本或 Markdown 字段跳过清洗。
_Avoid_: NoXss, SkipXss, RawHtml, IgnoreXss

**IpAccessMode**:
IP 访问控制校验模式枚举，定义白名单（WHITE_LIST，仅允许列表内 IP 访问）与黑名单（BLACK_LIST，拒绝列表内 IP 访问）策略。
_Avoid_: IpRuleType, AccessPolicy, FirewallMode

**IpAccessControlFilter**:
URL 路径级 IP 访问控制过滤器，基于 Ant 风格路径匹配对未授权客户端真实 IP 实施快速无状态阻断。
_Avoid_: FirewallFilter, SafeIpFilter, ClientFilter

**IpAccessControlAspect**:
基于 AOP 环绕拦截的声明式 `@IpAccessControl` 注解切面，解析方法与类级访问控制规则并执行精细化权限拦截。
_Avoid_: IpAspect, FirewallAspect, IpGuardAspect

**IpAccessControlProperties**:
IP 访问控制运行时配置属性，以 ateng.boot.web.ip-access-control 为前缀，提供黑白名单模式、规则清单与物理 403 切换控制。
_Avoid_: IpProperties, FirewallProperties

**ApiVersionCondition**:
实现 Spring MVC 原生 RequestCondition 的版本匹配条件，负责从 URL 路径前缀（/v{version}/）、请求头（X-API-Version）中提取版本并支持缺省默认版本回退。
_Avoid_: VersionCondition, RequestVersionCondition

**ApiVersionRequestMappingHandlerMapping**:
API 版本感知型请求映射处理器，继承 RequestMappingHandlerMapping，自动挂载 ApiVersionCondition 并自动扩展 /v{version} 路径前缀模式。
_Avoid_: VersionHandlerMapping, ApiVersionMapping

**ApiVersionProperties**:
API 多版本控制运行时配置属性，以 ateng.boot.web.api-version 为前缀，提供缺省默认版本、标头名称与路径前缀启闭控制。
_Avoid_: VersionProperties, ApiProperties





