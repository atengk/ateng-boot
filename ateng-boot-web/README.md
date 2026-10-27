# ateng-boot-web Web 运行时增强套件

> 提供基于 Filter 的全链路追踪、全局统一异常安全防御拦截、Jackson 53 位精度防失真与时间格式化、自适应幂等防重、自适应限流、动态安全 CORS、双向加签验签、审计日志与安全流式文件下载辅助。

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-web` 专注于提升 Web 接口层的高可用、安全性与工程规范。依托 Spring MVC 与 Servlet 标准，通过防御性自动装配为 Controller 传输边界提供全方位开箱即用护航。**坚守显式强类型契约不变量**，坚决杜绝 `@ResponseBodyAdvice` 全局自动拦截包装，所有接口返回值 100% 由强类型 `Result<T>` 显式自洽。

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-web</artifactId>
</dependency>
```

---

## 2. 特性与公共 API 全景速查矩阵

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **链路追踪** | HTTP 链路追踪过滤器与门面 | `TraceFilter`, `TraceUtil` | `ateng.boot.web.trace.enabled`, `header` (或 `header-name`) | `TraceUtil.getTraceId()`, `setTraceId(id)`, `clear()`, MDC `traceId` 注入并在 `finally` 严格清理 | `TraceFilterTest.java`, `TraceUtilTest.java`, `WebRuntimePipelineSampleControllerTest.java` |
| **全局异常防御** | 全局统一异常拦截与生产脱敏 | `GlobalExceptionHandler` | - | 拦截 `BusinessException`（双模因果堆栈）、`MethodArgumentNotValidException`、`HandlerMethodValidationException`、`BindException`、`ConstraintViolationException`、400/404/405/500 脱敏 | `GlobalExceptionHandlerTest.java`, `WebContractSampleControllerTest.java` |
| **精度与时间规范** | 53 位雪花精度防截断与时间序列化 | `JacksonCustomizer` | `ateng.boot.web.long-to-string` | Long/BigInteger 序列化为 String，标准 `yyyy-MM-dd HH:mm:ss` 格式化，不覆盖原生 `ObjectMapper` | `JacksonCustomizerTest.java`, `WebContractSampleControllerTest.java` |
| **接口审计日志** | 声明式接口耗时审计与安全防 OOM | `@WebLog`, `WebLogAspect` | `ateng.boot.web.log.enabled` | `@WebLog(value, printParams, printResult, excludeParams)`，大文件 Multipart 自动占位防 OOM | `WebLogAspectTest.java`, `WebAuditSampleControllerTest.java` |
| **自适应限流防刷** | 三级自适应令牌桶与多维限流 | `@RateLimiter`, `LimitType`, `RateLimiterStore`, `InMemoryRateLimiterStore`, `RedisRateLimiterStore`, `RedissonRateLimiterStore` | `ateng.boot.web.ratelimit.enabled`, `in-memory.max-capacity`, `in-memory.clean-interval` | `@RateLimiter(key, time, count, limitType)`，支持默认/IP/用户/租户多维限流防刷 | `RateLimiterAspectTest.java`, `AtengRateLimiterAutoConfigurationTest.java`, `InMemoryRateLimiterStoreTest.java`, `WebTrafficSampleControllerTest.java` |
| **自适应防重幂等** | 三级自适应防重幂等引擎 | `@Idempotent`, `IdempotentStore`, `InMemoryIdempotentStore`, `RedisIdempotentStore`, `RedissonIdempotentStore` | `ateng.boot.web.idempotent.enabled`, `key-prefix`, `in-memory.max-capacity`, `in-memory.clean-interval` | `@Idempotent(key, timeout, message, delKeyOnException)`，支持 SpEL 动态提取业务键、自动防抖、业务异常释放 | `IdempotentAspectTest.java`, `AtengIdempotentAutoConfigurationTest.java`, `InMemoryIdempotentStoreTest.java`, `WebTrafficSampleControllerTest.java` |
| **云原生优雅停机** | K8s 就绪探针排空与容器生命周期治理 | `AtengGracefulShutdownHandler`, `PreStopEndpoint`, `PreStopController` | `ateng.boot.web.shutdown.enabled`, `drain-timeout`, `smart-lifecycle-enabled`, `actuator-endpoint-enabled`, `mvc-endpoint-enabled`, `mvc-endpoint-path` | 翻转 `ReadinessState.REFUSING_TRAFFIC`，受管静默排空等待（`drain-timeout: 10s`），`AtomicBoolean` 状态机排空幂等，支持 Actuator `/actuator/pre-stop` 与 MVC `/pre-stop` 双模端点，`SmartLifecycle` 兜底 | `AtengGracefulShutdownHandlerTest.java`, `PreStopEndpointTest.java`, `PreStopControllerTest.java`, `AtengGracefulShutdownAutoConfigurationTest.java`, `WebRuntimePipelineSampleControllerTest.java` |
| **慢请求主动告警** | 长耗时慢请求性能主动告警与长连接放行 | `SlowRequestFilter` | `ateng.boot.web.slow-request.enabled`, `threshold`, `exclude-urls` | 请求出口处计算总耗时，超过阈值（强类型 Duration）输出包含 TraceId、URI、Method、耗时与 ClientIp 的 `[SLOW_REQUEST]` 格式化 WARN 日志，自动放行 WebSocket、SSE、文件下载与异步挂起请求，支持 Ant 路径白名单 | `SlowRequestFilterTest.java`, `SlowRequestMockMvcTest.java`, `WebRuntimePipelineSampleControllerTest.java` |
| **结构化操作审计** | 企业级操作审计切面与存储解耦 SPI | `@OperateLog`, `OperateLogAspect`, `OperateLogStorageProvider`, `DefaultOperateLogStorageProvider`, `OperateLogEvent` | `ateng.boot.web.operate-log.enabled` | `@OperateLog(module, type, description, saveParams, saveResult, excludeParams)`，自动关联线程上下文（操作人/租户），出参自动联动 `@FieldSensitive` 视图脱敏，保留原始异常因果链，支持异步存储 SPI 与开箱即用 JSON 保底 | `OperateLogAspectTest.java`, `OperateLogAutoConfigurationTest.java`, `WebAuditSampleControllerTest.java` |
| **请求体可重读管道** | 通用请求体重复多次读取与载荷防爆管道 | `ContentCachingFilter`, `CachedBodyHttpServletRequest` | `ateng.boot.web.body-cache.max-body-size`, `sign.enabled`, `payload-log.enabled` | 自动包装 POST/PUT/PATCH 请求为可重复读包装器，支持多次重复消费输入流，单次超限（DataSize）快速失败截断防 OOM，自动跳过表单与文件上传，旧版 `RequestBodyCachingFilter` 保持向后兼容委托 | `ContentCachingFilterTest.java`, `ContentCachingAutoConfigurationTest.java`, `ContentCachingMockMvcTest.java`, `WebRuntimePipelineSampleControllerTest.java` |
| **载荷采样日志** | HTTP 请求/响应双向安全采样与截断防 OOM | `PayloadLoggingFilter` | `ateng.boot.web.payload-log.enabled`, `max-payload-size`, `exclude-urls` | 请求出口处单行聚合输出包含 TraceId、Method、URI、Status、Cost、ReqBody 与 RespBody 的 `[HTTP_PAYLOAD]` 日志，内建 MIME 过滤、凭据正则掩码（`***`）、强类型截断（默认 4KB）、状态自适应提升（$\ge 400$ 提升为 WARN）并在 `finally` 确定性执行 `copyBodyToResponse()` | `PayloadLoggingFilterTest.java`, `PayloadLoggingMockMvcTest.java` |
| **双向加签验签** | API 报文防篡改防重放加签验签引擎 | `@ApiSign`, `ApiSigner`, `HmacSha256Signer`, `RsaSigner`, `Sm2Signer`, `SignKeyProvider`, `DefaultSignKeyProvider`, `SignCanonicalUtil`, `RequestBodyCachingFilter`, `CachedBodyHttpServletRequest` | `ateng.boot.web.sign.enabled`, `algorithm`, `app-id`, `secret-key`, `public-key`, `private-key`, `expire`, `max-body-size`, `header-app-id`, `header-timestamp`, `header-nonce`, `header-signature` | `@ApiSign(expireSeconds, signResponse)`，支持 HMAC-SHA256 对称、RSA 非对称与国密 SM2/SM3 非对称算法，请求体可重读包装，Nonce 防重放与响应加签 | `ApiSignerTest.java`, `Sm2SignerTest.java`, `SignCanonicalUtilTest.java`, `AtengApiSignAutoConfigurationTest.java`, `RequestBodyCachingFilterTest.java`, `WebSecuritySignSampleControllerTest.java`, `WebSecuritySignRsaSampleTest.java`, `WebSecuritySignSm2SampleTest.java` |
| **安全响应头防御** | 等保三级基线安全响应头注入与容器指纹剥离 | `SecurityHeadersFilter`, `SecurityHeadersProperties` | `ateng.boot.web.security-headers.enabled`, `content-type-options`, `frame-options`, `xss-protection`, `referrer-policy`, `content-security-policy`, `permissions-policy`, `remove-server-header` | 注入 `X-Content-Type-Options: nosniff`、`X-Frame-Options: SAMEORIGIN`、`X-XSS-Protection: 1; mode=block`、`Referrer-Policy: strict-origin-when-cross-origin` 等基准头，剥离容器 `Server` 头，`containsHeader` 守卫防覆盖 | `SecurityHeadersFilterTest.java`, `AtengWebAutoConfigurationTest.java`, `WebSecurityHeadersAndEtagSampleTest.java` |
| **ETag 协商缓存** | 基于浅层哈希的内容协商缓存与 304 节省带宽 | `ShallowEtagHeaderFilter`, `EtagProperties` | `ateng.boot.web.etag.enabled`, `write-weak-etag` | 条件自动装配 Spring 原生 `ShallowEtagHeaderFilter`，自动计算内容摘要并生成 `ETag` 响应头，配合 `If-None-Match` 返回 `304 Not Modified` 节省网络带宽 | `AtengWebAutoConfigurationTest.java`, `WebSecurityHeadersAndEtagSampleTest.java` |
| **客户端 IP 提取** | 多级反向代理穿透与安全清洗 | `IpUtil` | - | `IpUtil.getClientIp()`, `getClientIp(request)`，穿透 X-Forwarded-For 等多级代理，过滤内网伪造，规整 IPv6 本地环回 | `IpUtilTest.java`, `WebRuntimePipelineSampleControllerTest.java` |
| **Servlet 上下文与下载** | 上下文安全门面与 RFC 6266 文件下载 | `ServletUtil` | - | `ServletUtil.getRequest()`, `getResponse()`, `getHeader()`, `writeDownload(response, filename, bytes/stream)`，RFC 6266 双重编码彻底杜绝浏览器乱码 | `ServletUtilTest.java`, `WebRuntimePipelineSampleControllerTest.java` |

---

## 3. 全链路追踪与 MDC 安全流转 (`TraceFilter` & `TraceUtil`)

### 场景痛点
微服务或单体应用在排查生产异常时，若没有全局链路 ID，海量并发日志交织在一起无法还原现场链路。

### 核心机制
- 在 HTTP 请求入口处自动提取入站请求头 `X-Trace-Id`；若无则通过 `IdUtil.fastSimpleUUID()`（无锁并发生成）自动初始化；
- 写入日志框架 MDC `traceId`，自动回写至 HTTP 响应头 `X-Trace-Id`；
- 在请求出口处（`finally` 块）严格清理当前线程的 MDC 现场，绝无线程池内存泄露或跨请求污染；
- 配合 Core 模块的 `ContextCopyingTaskDecorator`，异步线程池可自动无缝透传该 `traceId`。

### 核心代码范式
```java
import io.github.atengk.boot.web.trace.TraceUtil;

// 1. 获取当前请求全链路 TraceId
String traceId = TraceUtil.getTraceId();

// 2. 编程式注入自定义 TraceId
TraceUtil.setTraceId("CUSTOM_TRACE_999");

// 3. 安全清理现场
TraceUtil.clear();
```

---

## 4. 全局统一异常安全防御拦截 (`GlobalExceptionHandler`)

### 场景痛点
1. 杜绝向前端直接泄露底层 SQL 语法报错、数据表结构、类名或死锁堆栈；
2. 将 JSR-303 参数校验错误、路由不存在、方法不支持与受控业务异常统一转译为标准 `Result<Void>` 契约；
3. 对携带根因 `cause` 的业务异常保留完整日志调用栈以供排障，对轻量前置校验保持低日志开销。

### 核心表现与双模日志机制
```java
// 1. 业务逻辑抛出自定义受控异常（无 cause：INFO 日志，轻量响应）
throw new BusinessException(403, "账户已被封禁");
// 统一返回 HTTP 200: {"code":403, "msg":"账户已被封禁", "data":null, "success":false, "traceId":"..."}

// 2. 转译底层驱动异常（有 cause：ERROR 日志打印完整堆栈与 traceId，前端安全脱敏）
try {
    gatewayClient.invoke();
} catch (IOException e) {
    throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "网关代理服务通信异常", e);
}

// 3. JSR-303 参数校验失败 (@Valid / @NotBlank / @Validated ValidList<T>)
// 拦截 MethodArgumentNotValidException、HandlerMethodValidationException (Spring 6.1+)、ConstraintViolationException 与 BindException
// 自动提取首个不合规字段的友好提示: {"code":400, "msg":"手机号格式不正确", "success":false, "traceId":"..."}

// 4. 未知根异常（如 NullPointerException, SQLException）
// 生产强制脱敏: {"code":500, "msg":"系统繁忙，请稍后再试", "success":false, "traceId":"..."}
// 真实完整异常堆栈仅打印在后台日志中，并强制携带 traceId 便于快速溯源排查
```

---

## 5. Jackson 53 位精度防失真与时间格式化 (`JacksonCustomizer`)

### 场景痛点
JavaScript 的 `Number` 遵循 IEEE 754 双精度浮点标准，最大安全整数为 $2^{53}-1$（`9007199254740991`）。后端 64 位雪花 ID（如 `104485532445560832`）传输至前端末尾几位会被强制截断归零。

### 解决方案
- 挂载 `Jackson2ObjectMapperBuilderCustomizer`，在序列化时将 `Long`、`long`、`BigInteger` 自动转换为 `String` 输出；
- 统一格式化时间类型：`LocalDateTime` (`yyyy-MM-dd HH:mm:ss`)、`LocalDate` (`yyyy-MM-dd`)、`LocalTime` (`HH:mm:ss`)；
- **坚决不覆盖底座 ObjectMapper Bean**，完美保留 Spring Boot 原生定制链条。

---

## 6. Web 请求接口审计日志与耗时观测 (`@WebLog`)

### 场景痛点
排查接口偶发慢响应或线上参数复现，手工写 `log.info` 繁琐易漏，且容易因序列化大文件导致 OOM。

### 核心代码范式
```java
@RestController
@RequestMapping("/orders")
public class OrderController {

    @PostMapping("/create")
    @WebLog(value = "创建订单", excludeParams = {"password", "pinCode"}, printCostTime = true)
    public Result<String> createOrder(@RequestBody OrderCreateDTO dto) {
        return Result.success(orderService.create(dto));
    }
}
```
### 日志呈现
```log
2026-10-16 16:00:00.123 [http-nio-8080-exec-1] [c8a14b0365734fb4864f7bdf753b890a] INFO  WebLogAspect : [WebLog] 开始请求: URL=[/orders/create], Method=[POST], IP=[192.168.1.1], Description=[创建订单], Params=[{"orderNo":"ORD001","amount":88.00}]
2026-10-16 16:00:00.155 [http-nio-8080-exec-1] [c8a14b0365734fb4864f7bdf753b890a] INFO  WebLogAspect : [WebLog] 请求完成: URL=[/orders/create], TimeCost=[32ms], Result=[{"code":200,"msg":"操作成功"}]
```

---

## 7. 三级自适应防重幂等引擎 (`@Idempotent`)

### 场景痛点
用户误触多次提交、网络抖动重发或前端无防抖导致订单重复创建。

### 自适应架构
采用 **Tier 1 (Redisson) -> Tier 2 (Redis) -> Tier 3 (单机内存)** 三级自适应抢占与兜底体系：引入 Redisson 时自动升级为分布式原子锁，仅有 Redis 时通过 `SETNX` 升级，无任何中间件时由纯原生单机内存安全支撑。

### 核心代码范式
```java
@PostMapping("/pay")
@Idempotent(
    key = "#order.orderNo",            // 支持 SpEL 表达式动态提取业务键
    timeout = 10,                      // 幂等锁定时间（默认秒）
    message = "支付请求处理中，请勿重复操作",
    delKeyOnException = true           // 业务异常时是否自动释放锁允许重试
)
public Result<String> payOrder(@RequestBody PayOrderDTO order) {
    return Result.success(payService.pay(order));
}
```

---

## 8. 三级自适应令牌桶限流防刷 (`@RateLimiter`)

### 场景痛点
恶意刷接口、短信被盗刷或秒杀突发瞬时流量压垮下游持久层。

### 核心代码范式
```java
@GetMapping("/sendSms")
@RateLimiter(
    key = "#phone",                    // 基于入参手机号限流
    time = 60,                         // 60 秒时间窗口
    count = 1,                         // 限制 1 次请求
    limitType = LimitType.IP,          // 叠加客户端 IP 联合防刷
    message = "短信发送过于频繁，请稍后再试"
)
public Result<Void> sendSms(@RequestParam String phone) {
    smsService.send(phone);
    return Result.success();
}
```

---

## 9. 安全动态 CORS 跨域与凭证反射 (`CorsOriginProvider`)

### 场景痛点
开启 `allowCredentials=true` 允许携带 Cookie 跨域时，W3C 规范严禁配置 `allowedOrigins: *`，否则会被浏览器直接拦截拒绝；硬编码域名又无法应对多测试环境动态切换。

### 核心代码范式
- **配置文件声明式通配匹配**：支持精确域名、通配符（如 `*.ateng.com`）与本地环回调试；
- **SPI 动态加载扩展**：
```java
import io.github.atengk.boot.web.cors.CorsOriginProvider;
import org.springframework.stereotype.Component;

@Component
public class DatabaseCorsOriginProvider implements CorsOriginProvider {
    @Override
    public boolean isAllowed(String requestOrigin) {
        // 从数据库或缓存中动态校验租户自定义独立域名
        return tenantDomainService.exists(requestOrigin);
    }
}
```

---

## 10. 双向 API 数据加签验签与防重放 (`@ApiSign`)

### 场景痛点
对外开放 OpenAPI、移动端通信防数据篡改与重放反爬攻击；政企、金融信创场景强制合规要求中国商用密码（国密 SM2/SM3）数字签名，且严禁引入沉重外部加密包。

### 核心代码范式
```java
@RestController
@RequestMapping("/api/open")
@ApiSign(
    expireSeconds = 300,                  // 5分钟防重放时间戳校验窗口（算法由 YAML 全局配置）
    signResponse = true                   // 是否在响应时对返回数据加签 (回传 X-Signature)
)
public class OpenApiController {

    @PostMapping("/data")
    public Result<DataVO> process(@RequestBody DataDTO dto) {
        return Result.success(dataService.handle(dto));
    }
}
```

### 国密 SM2/SM3 签名算法引擎
模块内建纯 JDK 17 原生国密 `Sm2Signer`（实现 `ApiSigner` 接口），零引入任何 Bouncy Castle 外部依赖：
```java
// 1. 生成全新 SM2 密钥对（包含 Hex 与 Base64 格式）
Sm2Signer.Sm2KeyPair keyPair = Sm2Signer.generateKeyPair();
String privateKeyHex = keyPair.privateKeyHex();
String publicKeyHex = keyPair.publicKeyHex();

// 2. 客户端使用私钥加签
Sm2Signer signer = new Sm2Signer();
String signature = signer.sign(canonicalString, privateKeyHex);

// 3. 服务端或验签方使用公钥验签（自动支持 ASN.1 DER 与裸 64 字节 Base64 签名）
boolean valid = signer.verify(canonicalString, signature, publicKeyHex);
```

---

## 11. Web 运行时工具集 (`IpUtil` & `ServletUtil`)

### 真实客户端 IP 提取 (`IpUtil`)
```java
// 穿透 Nginx / HAProxy / SLB 等多级反向代理，安全过滤内网伪造 IP 并规整 IPv6 本地环回 (::1 -> 127.0.0.1)
String clientIp = IpUtil.getClientIp();
```

### Servlet 上下文门面与安全流式下载 (`ServletUtil`)
```java
import io.github.atengk.boot.web.util.ServletUtil;

// 1. 获取当前请求与响应对象
HttpServletRequest request = ServletUtil.getRequest();
HttpServletResponse response = ServletUtil.getResponse();

// 2. 快捷获取请求头与 Cookie
String token = ServletUtil.getHeader("Authorization");
String cookie = ServletUtil.getCookieValue("SESSION_ID");

// 3. 判断是否为 JSON 或 AJAX 请求
boolean isJson = ServletUtil.isJsonRequest(request);

// 4. 一键安全流式下载文件（RFC 6266 双重文件名编码，彻底杜绝 Edge/Chrome/Safari 中文乱码）
byte[] pdfBytes = reportService.exportPdf();
ServletUtil.writeDownload(response, "2026年半年度财务分析.pdf", pdfBytes);

// 5. 或通过输入流零拷贝写出
InputStream in = fileService.openStream();
ServletUtil.writeDownload(response, "数据清单.csv", in);

// 6. 配合 EasyExcel 等报表引擎仅设置安全下载头
ServletUtil.setDownloadHeader(response, "用户花名册.xlsx");
```

---

## 12. 声明式企业级操作审计切面与存储解耦 SPI (`@OperateLog` & `OperateLogStorageProvider`)

### 场景痛点
企业级系统通常要求对关键业务操作（如增、删、改、查敏感数据）记录完整的结构化审计轨迹。然而传统手写审计方案面临严重痛点：
1. **代码侵入与繁琐耦合**：在各个 Controller 或 Service 中侵入式手写审计入库代码，与核心业务杂糅；
2. **上下文缺失断层**：难以自动且完整对齐全链路四维上下文（TraceId、UserId、Username、TenantId、ClientIp）；
3. **敏感数据污染与内存真明文破坏**：在记录出参时，若直接对实体属性打星号脱敏，将严重破坏 [ADR-0012](../docs/adr/0012-lifecycle-separated-data-security.md) 确立的“内存真明文”不变量，导致掩码脏数据被持久化回写至数据库；
4. **存储紧密耦合**：审计日志若硬编码死绑定于某张本地数据表，系统难以向 Elasticsearch、Kafka 等大数据或集中审计分析平台平滑演进。

### 核心机制
- **声明式 AOP 切面**：在 Controller 方法上标注 `@OperateLog`，支持声明模块名称（`module` 或 `title`）、操作类型（`type`，依托 Core 模块的 `OperateTypeEnum`）、业务描述（`description`）、入参/出参保存开关（`saveParams`/`saveResult`）及敏感参数排除列表（`excludeParams`）；
- **四维上下文全链路对齐**：切面执行时自动穿透并提取全链路快照：MDC `traceId`、当前操作人 ID/姓名（`UserContextHolder`）、当前租户 ID（`TenantContextHolder`）及真实客户端 IP（`IpUtil.getClientIp()`）；
- **坚守“内存真明文”不变量**：出参记录时，自动联动实体上的 `@FieldSensitive` 注解完成脱敏；脱敏仅针对克隆/序列化副本生效，实体在 JVM 内存中始终 100% 保持原始真明文；
- **存储解耦 SPI 架构**：对外暴露 `OperateLogStorageProvider` SPI 扩展点，开箱提供 `DefaultOperateLogStorageProvider`（基于受管 SLF4J 异步格式化输出 JSON），用户可通过实现接口自由投递至 Kafka、Elasticsearch 或业务表；
- **异常因果透明传递**：若业务方法抛出异常，切面在捕获后准确标记操作状态为失败（`status = 1`）并记录异常摘要，在 `finally` 块完成异步事件投递后 100% 原样重抛原始异常，绝不吞咽污染调用链。

### 核心代码范式
```java
@RestController
@RequestMapping("/users")
public class UserController {

    @PostMapping
    @OperateLog(
        module = "用户管理",
        type = OperateTypeEnum.INSERT,
        description = "新增系统用户",
        excludeParams = {"password"} // 敏感入参参数名自动脱敏排除
    )
    public Result<String> createUser(@RequestBody CreateUserDTO dto) {
        return Result.success(userService.create(dto));
    }

    @GetMapping("/{id}")
    @OperateLog(module = "用户中心", type = OperateTypeEnum.QUERY, description = "查询用户详情")
    public Result<UserVO> getUser(@PathVariable Long id) {
        // 出参 UserVO 挂载的 @FieldSensitive 手机号/身份证字段在审计日志中自动脱敏，但内存中保持真明文
        return Result.success(userService.getById(id));
    }
}
```

### 全真控制台日志呈现
```log
2026-10-18 19:43:02.311 INFO  [http-nio-8080-exec-1] [traceId: trace-user-001] i.g.a.b.w.a.DefaultOperateLogStorageProvider : [OPERATE_LOG] {"traceId":"trace-user-001","module":"用户管理","type":"INSERT","description":"新增系统用户","method":"io.github.atengk.boot.web.controller.UserController.createUser()","reqMethod":"POST","clientIp":"127.0.0.1","operUserId":10001,"operUserName":"ateng","tenantId":1,"operParams":"{\"username\":\"ateng\",\"password\":\"[EXCLUDED]\"}","jsonResult":"\"created:ateng\"","status":0,"costTime":15,"operTime":"2026-10-18T19:43:02.310"}
```

### 生产扩展接管：自定义存储 SPI
当需要将审计事件集中投递至 Kafka 或 Elasticsearch 时，仅需实现 `OperateLogStorageProvider` 并声明为 Spring Bean：
```java
@Component
public class KafkaOperateLogStorageProvider implements OperateLogStorageProvider {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaOperateLogStorageProvider(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void store(OperateLogModel model) {
        kafkaTemplate.send("topic-audit-logs", JsonUtil.toJson(model));
    }
}
```

---

## 13. 通用请求体可重读管道与载荷防爆 (`ContentCachingFilter`)

### 场景痛点
Servlet 规范中的原生 `ServletInputStream` 为阅后即焚的一次性输入流。在 API 验签（`@ApiSign`）、请求报文采样日志（`PayloadLoggingFilter`）以及业务流式解析等多个组件并存时，极易发生流枯竭冲突；同时若无边界地在内存中随意全量缓存，极易遭超大文件上传或恶意超大报文攻击，导致 JVM 堆内存瞬间被打爆产生 OOM。

### 核心机制
- **通用可重复消费底座**：自动包装为 `CachedBodyHttpServletRequest`，将原始字节缓存于受管的内存数组中，并发重写 `getInputStream()` 与 `getReader()`，支持同一次请求内下游所有过滤器、切面与 Controller 多次重复读取；
- **强类型载荷上限截断防 OOM**：受 `ateng.boot.web.body-cache.max-body-size: 2MB`（强类型 `DataSize`）严格约束。无论是 `Content-Length` 标头超限，还是无长度分块传输在读取中达到上限，均立即抛出 `BusinessException(CommonResultCode.REQUEST_BODY_TOO_LARGE)` 实施快速失败；
- **智能免打扰放行**：仅拦截 POST/PUT/PATCH 且为文本或 JSON 的请求；文件上传（`multipart/form-data`）与表单（`application/x-www-form-urlencoded`）自动穿透放行；
- **零开销按需激活**：由 `ContentCachingCondition` 驱动，仅在配置开启了加签验签（`sign.enabled=true`）或 Payload 采样（`payload-log.enabled=true`）时才自动激活；两者均未开启时跳过装配，实现运行时零额外包装与零性能损耗；
- **向后兼容**：旧版 `RequestBodyCachingFilter` 声明 `@Deprecated`，内部 100% 委托至 `ContentCachingFilter`。

### 核心代码范式
下游 Controller 或 Filter 无需任何特殊感知，直接按标准 Servlet API 重复消费即可：
```java
@PostMapping("/api/verify")
public Result<String> verify(HttpServletRequest request) throws IOException {
    // 第一次读取请求体
    byte[] firstRead = request.getInputStream().readAllBytes();
    
    // 第二次在同一次请求中再次读取输入流（验证流重复消费能力）
    byte[] secondRead = request.getInputStream().readAllBytes();
    
    // 亦可直接强转获取底层缓存字节副本
    if (request instanceof CachedBodyHttpServletRequest cachedRequest) {
        byte[] body = cachedRequest.getCachedBody();
    }
    return Result.success("ok");
}
```

### 生产配置范式
```yaml
ateng:
  boot:
    web:
      body-cache:
        # 通用请求体可重读最大缓存载荷上限（强类型 DataSize 防 OOM，默认 2MB）
        max-body-size: 2MB
```

---

## 14. 云原生就绪探针排空与优雅停机治理 (`AtengGracefulShutdownHandler`)

### 场景痛点
Kubernetes 在滚动发布或 Pod 缩容销毁时，默认会并行向 Pod 发送 `SIGTERM` 信号并将 Pod 从 Service Endpoints 中摘除。然而，由于 kube-proxy 与 Ingress 网关路由表同步存在客观延迟（通常需 1~5 秒），若容器直接关闭端口停止监听，极易导致上游在途请求遭遇连接重置、HTTP 502 Bad Gateway 或 504 超时。

### 核心机制
- **就绪探针主动拒绝**：收到下线信号时，自动调用 Spring 核心事件 `AvailabilityChangeEvent.publish(publisher, this, ReadinessState.REFUSING_TRAFFIC)`，使就绪探针即刻失效，驱动网关主动剔除实例；
- **静默排空与在途保活**：在就绪探针拒绝后，受管安全阻塞等待可配置的静默排空时长（`drain-timeout: 10s`），为在途请求提供充足时间处理完毕；
- **双模主动端点**：
  - **Actuator 环境**：自动注册 `@Endpoint(id = "preStop")`，暴露 `/actuator/pre-stop`（支持 GET 与 POST）；
  - **无 Actuator 环境**：条件降级自动注册轻量级 MVC 控制器 `PreStopController`，暴露 `/pre-stop`（支持 GET 与 POST）；
- **SIGTERM 兜底保护**：实现 `SmartLifecycle`（优先级 `Integer.MAX_VALUE`，早于 WebServer 停机执行），在未配置 K8s preStop 脚本直接收到终止信号时也能自动触发就绪探针拒绝与静默排空保护；
- **原子布尔幂等防重入**：内部基于 `AtomicBoolean` 状态机抢占，保证无论是 preStop 钩子还是 SIGTERM 信号触发，多次调用仅执行一次排空等待，绝不叠加延时。

### K8s Deployment 生产配置范式
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ateng-app
spec:
  template:
    spec:
      containers:
        - name: app
          image: ateng-app:1.0.0
          lifecycle:
            preStop:
              httpGet:
                path: /actuator/pre-stop  # 无 Actuator 时自动降级改为 /pre-stop
                port: 8080
          # 终止宽限期（建议大于 drain-timeout）
          terminationGracePeriodSeconds: 30
```

---

## 15. 长耗时慢请求性能主动告警 (`SlowRequestFilter`)

### 场景痛点
在高并发微服务与 Web 应用中，部分接口可能因慢 SQL、第三方外部 RPC 延迟或计算密集导致响应时间劣化。若缺乏主动监控手段，运维人员往往只能等到客户端报超时或系统负载飙升时才后知后觉。同时，若无脑拦截所有长耗时请求，极易对本就需要长时间保持的长连接（如 WebSocket、Server-Sent Events、大文件下载分块）产生大量无意义误报。

### 核心机制
- **出口统一耗时统计**：过滤器注册于 `TraceFilter.ORDER + 3`，在请求出口 `finally` 阶段精确统计处理耗时；
- **强类型阈值判定**：耗时达到或超过配置阈值（`threshold: 3s`，支持 `Duration` 格式如 `500ms`, `3s` 等）时，输出格式化 WARN 告警日志；
- **全链路四维元数据**：告警日志完整携带 MDC 全链路 `traceId`、HTTP 方法、请求 URI、耗时（ms）、阈值（ms）与真实客户端 IP；
- **智能长连接免打扰放行**：
  - 自动识别并放行请求/响应中的 `Upgrade` 握手标头（WebSocket 升级）；
  - 自动识别并放行 `text/event-stream`（SSE 事件流）；
  - 自动识别并放行 `application/octet-stream`（二进制流与文件分块下载）；
  - 自动识别并放行 `request.isAsyncStarted()` 异步调度与挂起任务；
- **白名单精准排除**：支持通过 `exclude-urls`（Ant 风格路径匹配，如 `/actuator/**`）自由排除运维或特殊业务路径；
- **因果异常链透明**：若下游控制器或过滤器抛出异常，在 `finally` 记录慢请求告警后 100% 原样重抛原始异常，绝不吞咽污染。

### 全真控制台日志呈现
```log
2026-10-18 20:29:14.888 WARN  [http-nio-8080-exec-1] [traceId: trace-mock-888] i.g.a.b.w.f.SlowRequestFilter : [SLOW_REQUEST] 检测到慢 HTTP 请求: method=[POST], uri=[/mock/slow], cost=[82ms], threshold=[50ms], clientIp=[127.0.0.1], traceId=[trace-mock-888]
```

### 生产配置与扩展范式
```yaml
ateng:
  boot:
    web:
      slow-request:
        # 是否启用慢请求性能告警过滤器（默认 true）
        enabled: true
        # 慢请求判定阈值（强类型 Duration，默认 3s，支持 500ms、3s 等）
        threshold: 3s
        # 慢请求监控排除路径白名单（Ant 风格匹配）
        exclude-urls:
          - "/actuator/**"
          - "/mock/slow-excluded"
```

当框架默认行为无法满足需要时，可通过覆盖 `@Bean` 自定义扩展接管：
```java
@Bean
public SlowRequestFilter customSlowRequestFilter() {
    return new SlowRequestFilter(Duration.ofSeconds(5), List.of("/actuator/**", "/special/**"));
}
```

---

## 16. HTTP Payload 双向安全采样打印与截断防 OOM (`PayloadLoggingFilter`)

### 场景痛点
生产环境排查业务交互故障或审计三方数据往来时，往往需要完整的请求与响应报文。然而直接打印原始载荷面临三大高危隐患：
1. **OOM 崩溃风险**：大文件上传、下载或深层大数据量分页若无脑转为全量内存字符串，极易迅速耗尽 JVM 堆内存导致服务雪崩；
2. **凭据安全泄露**：用户的登录密码、API Token、SecretKey 等若以明文被打印至日志，将触犯数据安全合规红线；
3. **响应截留挂起**：若使用 `ContentCachingResponseWrapper` 拦截响应体后，因异常等分支遗漏调用 `copyBodyToResponse()`，将直接导致客户端无法接收任何响应内容。

### 核心机制
- **底座管道协同与流可重读**：位于 `ContentCachingFilter`（`ORDER + 1`）之后，执行优先级为 `TraceFilter.ORDER + 2`，无缝复用可重读请求包装器 `CachedBodyHttpServletRequest`；
- **MIME 智能过滤防卫**：仅对纯文本与结构化数据（`application/json`、`application/xml`、`text/plain`、`application/x-www-form-urlencoded` 等）进行采样，二进制媒体（`multipart/*`、`application/octet-stream`、`image/*` 等）直接跳过并输出 `[BINARY_STREAM]` 占位；
- **强类型截断防卫**：严格遵循 `max-payload-size`（强类型 `DataSize`，默认 `4KB`），超出阈值部分强制截断并追加 ` ... [TRUNCATED]` 标识；
- **敏感凭据正则掩码**：对请求体与响应体中出现的 `password`、`secret`、`token`、`authorization` 等字段，内建轻量纯原生正则表达式自动掩码替换为 `***`；
- **响应确定性释放不变量**：在 `finally` 嵌套块中 100% 确保调用 `responseWrapper.copyBodyToResponse()`，彻底杜绝异常中止导致的客户端挂起；
- **状态码自适应提升**：HTTP 响应状态码 $\ge 400$ 时，单行聚合日志级别自动由 `INFO` 提升为 `WARN`；
- **单行聚合日志**：日志统筹聚合 MDC `traceId`、HTTP 方法、请求 URI、状态码、耗时、请求体与响应体，单行呈现无日志交错。

### 全真控制台日志呈现
```log
2026-10-18 20:43:16.120 INFO  [http-nio-8080-exec-1] [traceId: trace-payload-001] i.g.a.b.w.f.PayloadLoggingFilter : [HTTP_PAYLOAD] traceId=[trace-payload-001], method=[POST], uri=[/mock/payload/echo], status=[200], cost=[12ms], reqBody=[{"username":"ateng","password":"***","token":"***"}], respBody=[{"code":200,"msg":"操作成功","data":{"username":"ateng","password":"***","token":"***"}}]
```

### 生产配置与扩展范式
```yaml
ateng:
  boot:
    web:
      payload-log:
        # 是否启用 HTTP Payload 双向采样安全日志过滤器（默认 false，按需开启）
        enabled: true
        # 单次最大采样截断字节数上限（强类型 DataSize，默认 4KB）
        max-payload-size: 4KB
        # 采样日志排除路径白名单（Ant 风格匹配）
        exclude-urls:
          - "/actuator/**"
          - "/mock/payload/excluded"
```

当框架默认行为无法满足需要时，可通过覆盖 `@Bean` 自定义扩展接管：
```java
@Bean
public PayloadLoggingFilter customPayloadLoggingFilter() {
    return new PayloadLoggingFilter(DataSize.ofKilobytes(8), List.of("/actuator/**", "/internal/**"));
}
```

---

## 17. HTTP 安全响应头防御与 ETag 协商缓存 (`SecurityHeadersFilter` & `ShallowEtagHeaderFilter`)

### 场景痛点
1. **容器指纹泄露**：Web 容器默认向外暴露 `Server: Apache-Coyote/1.1` 等响应头，极易被自动化探测扫描识别出确切底层中间件版本进而遭受定向漏洞攻击；
2. **等保三级基线防御缺失**：缺失 `X-Content-Type-Options: nosniff`（MIME 类型嗅探）、`X-Frame-Options`（点击劫持）、`X-XSS-Protection` 与 `Referrer-Policy` 响应头，难以通过企业安全合规基线与第三方渗透审计；
3. **低频静态/字典接口带宽浪费**：客户端频繁轮询或读取相对固定的业务字典与元数据，缺乏标准 ETag / 304 协商缓存机制，造成不必要的网络带宽与服务器吞吐消耗。

### 核心机制与不变量
- **安全响应头防御 (`SecurityHeadersFilter`)**：
  - 默认开启（`ateng.boot.web.security-headers.enabled=true`），优先级仅次于慢请求过滤器（`ORDER = Ordered.HIGHEST_PRECEDENCE + 4`）；
  - 自动向 HTTP 响应注入标准基线响应头：
    - `X-Content-Type-Options: nosniff`
    - `X-Frame-Options: SAMEORIGIN`
    - `X-XSS-Protection: 1; mode=block`
    - `Referrer-Policy: strict-origin-when-cross-origin`
    - 可选配置 `Content-Security-Policy` 与 `Permissions-Policy`
  - **`containsHeader` 守卫不变量**：仅在响应中不存在同名响应头时注入，严格保障反向代理网关或下游业务 Controller 自定义设置的特定安全头不被覆盖；
  - **`Server` 容器特征彻底压制**：通过 `SecurityHeadersResponseWrapper` 拦截并过滤下游所有写向 `Server` 响应头的操作，彻底消除应用服务器特征。
- **ETag 协商缓存 (`ShallowEtagHeaderFilter`)**：
  - 按需启用（`ateng.boot.web.etag.enabled=true`，默认 `false`）；
  - 条件装配 Spring 官方 `ShallowEtagHeaderFilter`（`ORDER = SecurityHeadersFilter.ORDER + 1`），自动对响应体字节计算哈希并输出 `ETag` 响应头；
  - 当客户端在后续请求头携带 `If-None-Match` 时，服务端自动校验摘要，命中未变更时立即返回 `304 Not Modified` 并清空响应体，节省网络带宽。

### 场景端到端代码范式
```java
// 1. 固定或低频变动字典数据接口（配合 ETag 实现 304 协商缓存）
@GetMapping("/cached-dict")
public Result<Map<String, String>> getCachedDict() {
    Result<Map<String, String>> result = Result.success(Map.of("status", "ACTIVE", "locale", "zh_CN"));
    result.setTimestamp(1700000000000L); // 生产场景中亦可返回静态资源或固定业务快照
    return result;
}

// 2. 控制器显式设置特定安全头（验证 containsHeader 防覆盖机制）
@GetMapping("/custom-security-headers")
public Result<String> getCustomSecurityHeaders(HttpServletResponse response) {
    response.setHeader("X-Frame-Options", "DENY"); // 保持 DENY，绝不被默认 SAMEORIGIN 覆盖
    return Result.success("custom-headers-ok");
}
```

### 生产配置与扩展范式
```yaml
ateng:
  boot:
    web:
      security-headers:
        # 是否启用安全响应头防御过滤器（默认 true）
        enabled: true
        # 是否注入 X-Content-Type-Options: nosniff（默认 true）
        content-type-options: true
        # X-Frame-Options 配置（默认 SAMEORIGIN；为 null 或空字符串则不注入）
        frame-options: "SAMEORIGIN"
        # X-XSS-Protection 配置（默认 1; mode=block；为 null 或空字符串则不注入）
        xss-protection: "1; mode=block"
        # Referrer-Policy 配置（默认 strict-origin-when-cross-origin；为 null 或空字符串则不注入）
        referrer-policy: "strict-origin-when-cross-origin"
        # Content-Security-Policy 配置（默认 null，按需配置 CSP 策略字符串）
        content-security-policy: null
        # Permissions-Policy 配置（默认 null，按需配置特性权限策略）
        permissions-policy: null
        # 是否压制并剥离容器 Server 响应头（默认 true，消除 Tomcat/Undertow 容器指纹）
        remove-server-header: true
      etag:
        # 是否启用基于 ShallowEtagHeaderFilter 的 ETag 协商缓存（默认 false，按需开启）
        enabled: true
        # 是否生成弱 ETag（形如 W/"..."，默认 false 为强 ETag）
        write-weak-etag: false
```

---

## 18. 模块配置属性参考 (`application.yml`)

```yaml
ateng:
  boot:
    web:
      # 是否启用 Web 模块整体增强（默认 true）
      enabled: true
      # 是否将 Long 序列化为 String（防前端雪花丢失精度，默认 true）
      long-to-string: true
      trace:
        # 是否启用 TraceFilter 链路追踪与 MDC 自动流转（默认 true）
        enabled: true
        # 链路追踪头名称（默认 X-Trace-Id；属性别名亦兼容 header）
        header-name: "X-Trace-Id"
      security-headers:
        # 是否启用安全响应头防御（默认 true）
        enabled: true
        # 是否注入 X-Content-Type-Options: nosniff（默认 true）
        content-type-options: true
        # X-Frame-Options 配置（默认 SAMEORIGIN）
        frame-options: "SAMEORIGIN"
        # X-XSS-Protection 配置（默认 1; mode=block）
        xss-protection: "1; mode=block"
        # Referrer-Policy 配置（默认 strict-origin-when-cross-origin）
        referrer-policy: "strict-origin-when-cross-origin"
        # Content-Security-Policy 策略（默认 null）
        content-security-policy: null
        # Permissions-Policy 权限策略（默认 null）
        permissions-policy: null
        # 是否剥离容器 Server 响应头（默认 true）
        remove-server-header: true
      etag:
        # 是否启用基于 ShallowEtagHeaderFilter 的 ETag 协商缓存（默认 false）
        enabled: false
        # 是否生成弱 ETag（默认 false 为强 ETag）
        write-weak-etag: false
      slow-request:
        # 是否启用慢请求性能告警过滤器（默认 true）
        enabled: true
        # 慢请求判定阈值（强类型 Duration，默认 3s）
        threshold: 3s
        # 慢请求监控排除路径白名单（Ant 风格匹配）
        exclude-urls:
          - "/actuator/**"
      shutdown:
        # 是否启用云原生流量排空与生命周期治理（默认 true）
        enabled: true
        # 就绪探针置为拒绝后静默排空等待时间（强类型 Duration，默认 10s）
        drain-timeout: 10s
        # 是否启用 SmartLifecycle 兜底保护（未配 preStop 收到 SIGTERM 时自动排空，默认 true）
        smart-lifecycle-enabled: true
        # 是否启用 Actuator preStop 端点（默认 true，当类路径存在 Actuator 时生效）
        actuator-endpoint-enabled: true
        # 是否启用 Spring MVC preStop 控制器（为 null 时自动判定：无 Actuator 时自动降级启用）
        mvc-endpoint-enabled: null
        # Spring MVC 降级端点路径（默认 /pre-stop）
        mvc-endpoint-path: "/pre-stop"
      operate-log:
        # 是否启用结构化操作审计切面（默认 true）
        enabled: true
      body-cache:
        # 通用请求体可重读最大缓存载荷上限（强类型 DataSize 防 OOM，默认 2MB）
        max-body-size: 2MB
      payload-log:
        # 是否启用 HTTP Payload 双向采样安全日志过滤器（默认 false，按需开启）
        enabled: false
        # 单次最大采样截断字节数上限（强类型 DataSize，默认 4KB）
        max-payload-size: 4KB
        # 采样日志排除路径白名单（Ant 风格匹配）
        exclude-urls:
          - "/actuator/**"
      log:
        # 是否装配 @WebLog 接口审计切面（默认 false，按需开启）
        enabled: true
      cors:
        # 是否开启通用跨域支持（默认 false）
        enabled: false
        # 允许跨域请求的源域名模式列表（支持精确域名、*.domain.com 通配与 *）
        allowed-origins:
          - "*"
        # 是否允许携带凭证（开启时自动安全反射请求源 Origin）
        allow-credentials: false
        # 是否自动放行本地 localhost 与 127.0.0.1 跨域调试（默认 true）
        allow-localhost: true
        # 跨域预检请求缓存有效时间（强类型 Duration 格式，支持 3600s, 1h 等，默认 3600s）
        max-age: 3600s
      idempotent:
        # 是否启用接口防重幂等切面与存储引擎（默认 true）
        enabled: true
        # 存储全局 Key 前缀（默认 idempotent:）
        key-prefix: "idempotent:"
        in-memory:
          # 单机内存存储软上限（防爆防 OOM，默认 10000）
          max-capacity: 10000
          # 定时清理过期 Key 周期（强类型 Duration，默认 60s）
          clean-interval: 60s
      ratelimit:
        # 是否启用接口限流防刷切面与存储引擎（默认 true）
        enabled: true
        in-memory:
          # 单机内存令牌桶最大容量软上限（默认 10000）
          max-capacity: 10000
          # 过期淘汰扫描周期（强类型 Duration，默认 60s）
          clean-interval: 60s
      sign:
        # 是否启用 API 接口双向加签验签与防重放引擎（默认 false，按需开启）
        enabled: false
        # 签名算法（支持 HMAC_SHA256、RSA、SM2，默认 HMAC_SHA256）
        algorithm: HMAC_SHA256
        # 对称密钥（HMAC_SHA256 算法下使用）
        secret-key: "YourSecretKey"
        # 非对称算法公钥（RSA X.509 Base64 或 SM2 Hex/Base64，用于入站验签）
        public-key: "YourPublicKey"
        # 非对称算法私钥（RSA PKCS#8 Base64 或 SM2 Hex/Base64，用于出站加签）
        private-key: "YourPrivateKey"
        # 全局签名时效窗口（强类型 Duration，默认 60s；向后兼容 expire-seconds: 60）
        expire: 60s
        # 请求体缓存最大载荷大小（强类型 DataSize 防 OOM，支持 2MB, 10MB 等，默认 2MB）
        max-body-size: 2MB
        # 应用标识请求头名称（默认 X-App-Id，支持动态定制）
        header-app-id: "X-App-Id"
        # 时间戳请求头/响应头名称（默认 X-Timestamp，支持动态定制）
        header-timestamp: "X-Timestamp"
        # 随机防重放串请求头/响应头名称（默认 X-Nonce，支持动态定制）
        header-nonce: "X-Nonce"
        # 签名串请求头/响应头名称（默认 X-Signature，支持动态定制）
        header-signature: "X-Signature"
```
