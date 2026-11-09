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

**TraceUtil**:
全链路追踪 MDC 与 HTTP 上下文操作门面，提供 traceId 的安全读取、写入与确定性清理。
_Avoid_: TraceHelper, MdcUtil, LogTraceUtil

**IpUtil**:
多级反向代理客户端真实 IP 提取工具，支持可信反向代理列表（trustedProxies）逆向清洗与伪造过滤，按序解析 X-Forwarded-For 等标头首个有效 IP，并安全兼容本地环回与 IPv6 规整。
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

**ShallowEtagHeaderFilter**:
基于 Spring 官方浅层 ETag 生成机制的条件协商缓存过滤器，对出站响应体计算哈希并在客户端 If-None-Match 命中时返回 HTTP 304 Not Modified 以节省网络带宽。
_Avoid_: EtagFilter, CacheFilter, HashFilter

**EtagProperties**:
ETag 协商缓存运行时配置属性，以 ateng.boot.web.etag 为前缀，提供开关与弱 ETag（write-weak-etag）控制。
_Avoid_: EtagConfig, CacheProperties

**XssMode**:
XSS 跨站脚本安全清洗模式枚举，定义实体转义（ESCAPE，默认将敏感字符转为 HTML 实体）与标签剔除（STRIP，直接剥离恶意脚本与 HTML 标签）两种防御模式。
_Avoid_: XssType, CleanMode, SanitizerMode

**XssCleaner**:
XSS 无害化安全清洗引擎，依托纯 JDK 原生字符串替换与正则算法实现高效实体转义与脚本标签剥离，零引入三方重型解析库。
_Avoid_: HtmlSanitizer, AntiXssUtil, XssUtil

**EnumDictionaryProperties**:
枚举字典 HTTP 运行时配置属性，以 ateng.boot.web.enum 为前缀，管控端点开关、路由路径前缀、扫描包清单与命名转换策略。
_Avoid_: EnumProperties, DictProperties, EnumEndpointProperties

**EnumNamingStrategy**:
枚举类短名转前端别名命名策略枚举，支持小驼峰（LOWER_CAMEL_CASE）、下划线（SNAKE_CASE）与原始类名（ORIGINAL）。
_Avoid_: NamingType, EnumCaseStrategy

**EnumDictionaryRegistry**:
枚举字典启动期扫描注册中心，负责解析指定包路径内所有实现 IBaseEnum 的枚举，检测别名冲突并预编译为不可变选项缓存。
_Avoid_: EnumManager, DictRegistry, EnumCache

**EnumDictionaryController**:
提供对外只读枚举字典查询的 HTTP 控制器，遵循统一 Result 响应契约，提供单个查询、批量合并及全量目录检索端点。
_Avoid_: EnumController, DictController, EnumEndpoint

**BaseEnumDeserializer**:
Jackson 泛型反序列化定制器，拦截 IBaseEnum 实现类型，支持将前端传入的整数或字符串状态码自动解析为强类型枚举实例。
_Avoid_: EnumJsonDeserializer, BaseEnumJsonCreator

**SignAlgorithm**:
API 数据签名算法枚举，支持 HMAC_SHA256 对称哈希、RSA 非对称与 SM2 国密非对称算法。
_Avoid_: SignatureType, SignMode, CryptoAlgorithm

**SignKeyProvider**:
签名密钥对与 SecretKey 动态提供者 SPI 接口，支持根据 appId 动态获取加验签密钥。
_Avoid_: KeyManager, SecretProvider, ApiKeyStore

**DefaultSignKeyProvider**:
基于本地配置属性（SignProperties）加载密钥的开箱即用默认提供者实现。
_Avoid_: SimpleSignKeyProvider, ConfigSignKeyProvider

**HmacSha256Signer**:
基于纯 JDK 原生 Mac 实现的 HMAC-SHA256 对称签名与验签引擎。
_Avoid_: HmacSigner, Sha256Signer

**RsaSigner**:
基于纯 JDK 原生 SHA256withRSA 实现的非对称数字签名与验签引擎。
_Avoid_: RsaSignatureEngine, RsaCryptoSigner

**SignCanonicalUtil**:
API 签名规范化规约工具，提供 Query 参数与 JSON 请求体字典序排序、键值拼接与防重放摘要基底构造。
_Avoid_: CanonicalHelper, SignUtil, UrlParamUtil

**LimitType**:
接口限流防刷维度枚举，支持默认接口方法级（DEFAULT）、客户端真实 IP（IP）、用户身份（USER）与多租户（TENANT）维度。
_Avoid_: RateLimitType, LimitDimension, FlowRuleType

**InMemoryRateLimiterStore**:
基于纯 JDK 原生并发容器与防爆容量软上限的单机令牌桶限流存储实现（Tier 3 兜底）。
_Avoid_: LocalRateLimiterStore, MemoryRateLimiterStore

**RedisRateLimiterStore**:
基于 Spring Data Redis 与原子 Lua 脚本的分布式令牌桶限流存储实现（Tier 2 次级）。
_Avoid_: DistributedRedisRateLimiter, RedisLimitStore

**RedissonRateLimiterStore**:
基于 RedissonClient RRateLimiter 原生分布式令牌桶的限流存储实现（Tier 1 最高优先级）。
_Avoid_: RedissonLimitStore, DistributedRedissonStore

**AtengRateLimiterAutoConfiguration**:
限流治理三级自适应自动装配类，负责按序探测 Redisson/Redis/InMemory 存储并装配 RateLimiterAspect。
_Avoid_: RateLimiterAutoConfig, RateLimiterConfiguration

**PreStopEndpoint**:
基于 Spring Boot Actuator 的云原生 Pod 生命周期流量排空端点，暴露 /actuator/pre-stop。
_Avoid_: PreStopActuator, DrainEndpoint

**PreStopController**:
无 Actuator 依赖时的轻量级 Spring MVC 流量排空控制器，暴露 /pre-stop。
_Avoid_: PreStopMvcController, ShutdownController

**ApplicationReadyCardListener**:
应用启动就绪事件监听器，探测服务端口、真实内网 IP 与接口文档端点并渲染一体化 ASCII 欢迎卡片。
_Avoid_: StartupCardListener, BannerPrinter, ReadyListener

**ProductionSanityChecker**:
生产环境（prod/production）高危配置防呆自检器，启动期探测 Swagger 公网暴露、Payload 全量日志与 H2 控制台并输出安全警示。
_Avoid_: SecuritySanityChecker, ProductionGuard, StartupChecker

**DynamicCorsConfigurationSource**:
基于属性配置与 CorsOriginProvider 的动态安全跨域配置源，实施请求时动态来源判定与凭据安全反射。
_Avoid_: DynamicCorsSource, CustomCorsConfigurationSource, WebCorsSource

**DefaultCorsOriginProvider**:
基于属性配置白名单与正则表达式模式的开箱即用默认跨域来源校验提供者。
_Avoid_: SimpleCorsOriginProvider, ConfigCorsOriginProvider

**CachedBodyHttpServletRequest**:
可重复读取请求体的 HttpServletRequest 包装器，支持载荷上限拦截防 OOM 与 HTTP 200 + 413 业务错误码快速失败。
_Avoid_: BodyCacheRequest, ReReadRequest, CachedRequest

**XssHttpServletRequestWrapper**:
XSS 安全清洗 HTTP 请求包装器，拦截 Query/Form 参数与非排除 Header 并实施无害化清洗与伪协议剥离。
_Avoid_: CleanRequestWrapper, AntiXssRequestWrapper, SafeRequest

**XssStringDeserializer**:
Jackson 字符串反序列化器，在 JSON 请求体反序列化为对象属性阶段根据 XssCleaner 执行无害化清洗，支持 @XssIgnore 字段级豁免。
_Avoid_: JsonXssCleaner, XssDeserializer, StringCleanDeserializer

**ApiSignAspect**:
基于 AOP 环绕拦截的 API 数据签名防篡改切面，解析 @ApiSign 契约，调用 ApiSigner 实施双向或单向验签与加签控制。
_Avoid_: SignAspect, SignatureAspect, DataSignAspect

**DocDetectionHelper**:
OpenAPI/Swagger 接口文档暴露探测辅助工具，用于应用启动就绪卡片与生产高危配置防呆探测。
_Avoid_: SwaggerHelper, DocUtil, OpenApiDetector



