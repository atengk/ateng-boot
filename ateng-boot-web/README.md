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
| **链路追踪** | HTTP 链路追踪过滤器与门面 | `TraceFilter`, `TraceUtil` | `ateng.boot.web.trace.enabled`, `header` (或 `header-name`) | `TraceUtil.getTraceId()`, `setTraceId(id)`, `clear()`, MDC `traceId` 注入并在 `finally` 严格清理 | `TraceFilterTest.java`, `TraceUtilTest.java`, `SampleApplicationIntegrationTest.java` |
| **全局异常防御** | 全局统一异常拦截与生产脱敏 | `GlobalExceptionHandler` | - | 拦截 `BusinessException`（双模因果堆栈）、`MethodArgumentNotValidException`、`BindException`、`ConstraintViolationException`、400/404/405/500 脱敏 | `GlobalExceptionHandlerTest.java`, `SampleApplicationIntegrationTest.java` |
| **精度与时间规范** | 53 位雪花精度防截断与时间序列化 | `JacksonCustomizer` | `ateng.boot.web.long-to-string` | Long/BigInteger 序列化为 String，标准 `yyyy-MM-dd HH:mm:ss` 格式化，不覆盖原生 `ObjectMapper` | `JacksonCustomizerTest.java`, `SampleApplicationIntegrationTest.java` |
| **接口审计日志** | 声明式接口耗时审计与安全防 OOM | `@WebLog`, `WebLogAspect` | `ateng.boot.web.log.enabled` | `@WebLog(value, printParams, printResult, excludeParams)`，大文件 Multipart 自动占位防 OOM | `WebLogAspectTest.java`, `SampleApplicationIntegrationTest.java` |
| **自适应限流防刷** | 三级自适应令牌桶与多维限流 | `@RateLimiter`, `LimitType`, `RateLimiterStore`, `InMemoryRateLimiterStore`, `RedisRateLimiterStore`, `RedissonRateLimiterStore` | `ateng.boot.web.ratelimit.enabled`, `in-memory.max-capacity`, `in-memory.clean-interval` | `@RateLimiter(key, time, count, limitType)`，支持默认/IP/用户多维限流防刷 | `RateLimiterAspectTest.java`, `AtengRateLimiterAutoConfigurationTest.java`, `InMemoryRateLimiterStoreTest.java`, `SampleRateLimiterIntegrationTest.java` |
| **结构化操作审计** | 企业级操作审计切面与存储解耦 SPI | `@OperateLog`, `OperateLogAspect`, `OperateLogStorageProvider`, `DefaultOperateLogStorageProvider`, `OperateLogEvent` | `ateng.boot.web.operate-log.enabled` | `@OperateLog(module, type, description, saveParams, saveResult, excludeParams)`，自动关联线程上下文（操作人/租户），出参自动联动 `@FieldSensitive` 视图脱敏，保留原始异常因果链，支持异步存储 SPI 与开箱即用 JSON 保底 | `OperateLogAspectTest.java`, `OperateLogAutoConfigurationTest.java` |
| **请求体可重读管道** | 通用请求体重复多次读取与载荷防爆管道 | `ContentCachingFilter`, `CachedBodyHttpServletRequest` | `ateng.boot.web.body-cache.max-body-size`, `sign.enabled`, `payload-log.enabled` | 自动包装 POST/PUT/PATCH 请求为可重复读包装器，支持多次重复消费输入流，单次超限（DataSize）快速失败截断防 OOM，自动跳过表单与文件上传，旧版 `RequestBodyCachingFilter` 保持向后兼容委托 | `ContentCachingFilterTest.java`, `ContentCachingAutoConfigurationTest.java`, `ContentCachingMockMvcTest.java` |
| **双向加签验签** | API 报文防篡改防重放加签验签引擎 | `@ApiSign`, `ApiSigner`, `HmacSha256Signer`, `RsaSigner`, `SignKeyProvider`, `DefaultSignKeyProvider`, `SignCanonicalUtil`, `RequestBodyCachingFilter`, `CachedBodyHttpServletRequest` | `ateng.boot.web.sign.enabled`, `algorithm`, `app-id`, `secret-key`, `public-key`, `private-key`, `expire`, `max-body-size`, `header-app-id`, `header-timestamp`, `header-nonce`, `header-signature` | `@ApiSign(expireSeconds, signResponse)`，支持 HMAC-SHA256 对称与 RSA 非对称算法，请求体可重读包装，Nonce 防重放与响应加签 | `ApiSignerTest.java`, `SignCanonicalUtilTest.java`, `AtengApiSignAutoConfigurationTest.java`, `RequestBodyCachingFilterTest.java`, `SampleApiSignIntegrationTest.java` |
| **客户端 IP 提取** | 多级反向代理穿透与安全清洗 | `IpUtil` | - | `IpUtil.getClientIp()`, `getClientIp(request)`，穿透 X-Forwarded-For 等多级代理，过滤内网伪造，规整 IPv6 本地环回 | `IpUtilTest.java` |
| **Servlet 上下文与下载** | 上下文安全门面与 RFC 6266 文件下载 | `ServletUtil` | - | `ServletUtil.getRequest()`, `getResponse()`, `getHeader()`, `writeDownload(response, filename, bytes/stream)`，RFC 6266 双重编码彻底杜绝浏览器乱码 | `ServletUtilTest.java` |

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

// 3. JSR-303 参数校验失败 (@Valid / @NotBlank)
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
对外开放 OpenAPI、移动端通信防数据篡改与重放反爬攻击。

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

## 12. 模块配置属性参考 (`application.yml`)

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
        # 签名算法（支持 HMAC_SHA256、RSA，默认 HMAC_SHA256）
        algorithm: HMAC_SHA256
        # 对称密钥（HMAC_SHA256 算法下使用）
        secret-key: "YourSecretKey"
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
