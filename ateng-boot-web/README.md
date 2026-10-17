# ateng-boot-web Web 运行时增强套件

> 提供基于 Filter 的链路追踪、全局异常防御拦截、Jackson 防失真与时间格式化、自适应幂等防重、自适应限流、动态 CORS、双向加签验签、审计日志与安全流式文件下载辅助。

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-web` 专注于提升 Web 接口层的高可用、安全性与工程规范。依托 Spring MVC 与 Servlet 标准，通过防御性自动装配为 Controller 层提供全方位开箱即用护航。

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-web</artifactId>
</dependency>
```

---

## 2. 全链路追踪与 MDC 安全流转 (`TraceFilter`)

### 场景痛点
微服务或单体架构在排查生产日志时，若没有全局链路 ID，海量并发日志交织在一起无法追踪。

### 核心机制
- 在 HTTP 请求入口处自动提取入站请求头 `X-Trace-Id`；若无则通过 `IdUtil.fastSimpleUUID()` 自动生成；
- 写入日志框架 MDC `traceId`，自动回写至 HTTP 响应头 `X-Trace-Id`，并在请求出口处安全清理，绝无线程池内存泄露；
- 日志输出模式推荐配置 `%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId:-NO-TRACE}] %-5level %logger{36} : %msg%n`；
- **全链路追踪门面 (`TraceUtil`)**：提供无侵入编程式提取与注入门面：
  ```java
  // 1. 获取当前请求全链路 TraceId
  String traceId = TraceUtil.getTraceId();

  // 2. 编程式注入自定义 TraceId
  TraceUtil.setTraceId("CUSTOM_TRACE_999");

  // 3. 安全清理现场
  TraceUtil.clear();
  ```

---

## 3. 全局统一异常处理与生产脱敏 (`GlobalExceptionHandler`)

### 场景痛点
杜绝直接向前端暴露底层 SQL 语法报错、数据表结构或死锁堆栈，同时将校验错误与业务异常统一转译为标准 `Result<T>` 契约。

### 核心表现
```java
// 1. 业务逻辑抛出自定义异常
throw new BusinessException(403, "账户已被封禁");
// 统一返回 HTTP 200: {"code":403, "msg":"账户已被封禁", "data":null, "success":false, "traceId":"..."}

// 2. JSR-380 参数校验失败 (@Valid / @NotNull)
// 自动提取首个不合规字段的友好提示: {"code":400, "msg":"手机号格式不正确", ...}

// 3. 未知未知根异常（如 NullPointerException, SQLException）
// 生产强制脱敏: {"code":500, "msg":"系统繁忙，请稍后重试", ...}
// 真实完整异常堆栈仅打印在后台日志中，便于结合 traceId 溯源排查
```

---

## 4. Jackson 53位精度防失真与时间格式化 (`JacksonCustomizer`)

### 场景痛点
JavaScript 的 `Number` 遵循 IEEE 754 双精度浮点标准，最大安全整数为 $2^{53}-1$（`9007199254740991`）。后端 64 位雪花 ID（如 `104485532445560832`）传输至前端末尾几位会被强制截断归零。

### 解决方案
- 挂载 `Jackson2ObjectMapperBuilderCustomizer`，在序列化时将 `Long`、`long`、`BigInteger` 自动转换为 `String` 输出；
- 统一格式化时间类型：`LocalDateTime` (`yyyy-MM-dd HH:mm:ss`)、`LocalDate` (`yyyy-MM-dd`)、`LocalTime` (`HH:mm:ss`)；
- **不覆盖底座 ObjectMapper Bean**，完美保留 Spring Boot 原生定制链条。

---

## 5. Web 请求接口审计日志与耗时观测 (`@WebLog`)

### 场景痛点
排查接口偶发慢响应或线上参数复现，手工写 `log.info` 繁琐易漏，且容易因序列化大文件导致 OOM。

### 核心代码范式
```java
@RestController
@RequestMapping("/orders")
public class OrderController {

    @PostMapping("/create")
    @WebLog(value = "创建订单", excludeParams = {"password", "pinCode"})
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

## 6. 三级自适应防重幂等引擎 (`@Idempotent`)

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

## 7. 三级自适应令牌桶限流防刷 (`@RateLimiter`)

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

## 8. 安全动态 CORS 跨域与凭证反射 (`CorsOriginProvider`)

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

## 9. 双向 API 数据加签验签与防重放 (`@ApiSign`)

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

## 10. Web 运行时工具集 (`IpUtil` & `ServletUtil`)

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
// EasyExcel.write(response.getOutputStream(), ...).sheet().doWrite(dataList);
```

---

## 11. 上下文感知受管异步线程池 (`@Async` & `AtengAsyncAutoConfiguration`)

### 场景痛点
使用 Spring `@Async` 时，如果不做定制，MDC `traceId` 与登录用户/租户上下文在子线程中会完全丢失，导致日志断链或数据越权。

### 核心机制
- 受管异步线程池已下沉至 `ateng-boot-core` 统一底座，Web 运行时天然继承，自动注册带有 `ContextCopyingTaskDecorator` 的 `ThreadPoolTaskExecutor` 作为主异步线程池；
- 异步任务执行前自动抓取主线程的四维上下文快照（MDC `traceId`、`UserContextHolder`、`TenantContextHolder` 与忽略租户标记），在子线程执行完成后安全清理并恢复现场；
- 支持自适应 CPU 核心数与优雅停机（`wait-for-tasks-to-complete-on-shutdown: true`）。

---

## 12. 模块配置属性参考 (`application.yml`)

```yaml
ateng:
  boot:
    web:
      enabled: true               # 是否启用 Web 模块整体增强（默认 true）
      long-to-string: true        # 是否将 Long 序列化为 String（防前端雪花丢失精度，默认 true）
      trace:
        enabled: true             # 是否启用 TraceFilter 链路追踪与 MDC 自动流转（默认 true）
        header-name: "X-Trace-Id" # 链路追踪头名称（默认 X-Trace-Id；属性别名亦兼容 header）
      log:
        enabled: true             # 是否装配 @WebLog 接口审计切面（默认 false，按需开启）
      cors:
        enabled: false            # 是否开启通用跨域支持（默认 false）
        allowed-origins:          # 允许跨域请求的源域名模式列表（支持精确域名、*.domain.com 通配与 *）
          - "*"
        allow-credentials: false  # 是否允许携带凭证（开启时自动安全反射请求源 Origin）
        allow-localhost: true     # 是否自动放行本地 localhost 与 127.0.0.1 跨域调试（默认 true）
      idempotent:
        enabled: true             # 是否启用接口防重幂等切面与存储引擎（默认 true）
        key-prefix: "idempotent:" # 存储全局 Key 前缀（默认 idempotent:）
        in-memory:
          max-capacity: 10000     # 单机内存存储软上限（防爆防 OOM，默认 10000）
          clean-interval: 60s     # 定时清理过期 Key 周期（默认 60s）
      ratelimit:
        enabled: true             # 是否启用接口限流防刷切面与存储引擎（默认 true）
        in-memory:
          max-capacity: 10000     # 单机内存令牌桶最大容量软上限（默认 10000）
          clean-interval: 60s     # 过期淘汰扫描周期（默认 60s）
      sign:
        enabled: false            # 是否启用 API 接口双向加签验签与防重放引擎（默认 false，按需开启）
        algorithm: HMAC_SHA256    # 签名算法（支持 HMAC_SHA256、RSA，默认 HMAC_SHA256）
        secret-key: "YourSecretKey" # 对称密钥（HMAC_SHA256 算法下使用）
        expire-seconds: 60        # 全局签名时效窗口（秒，默认 60 秒；可被方法级 @ApiSign(expireSeconds) 覆盖）
        max-body-size: 2097152    # 请求体缓存最大字节数（防 OOM，默认 2MB）
        header-app-id: "X-App-Id" # 应用标识请求头名称（默认 X-App-Id，支持动态定制）
        header-timestamp: "X-Timestamp" # 时间戳请求头/响应头名称（默认 X-Timestamp，支持动态定制）
        header-nonce: "X-Nonce"   # 随机防重放串请求头/响应头名称（默认 X-Nonce，支持动态定制）
        header-signature: "X-Signature" # 签名串请求头/响应头名称（默认 X-Signature，支持动态定制）

    # 异步线程池配置（统一由 ateng-boot-core 底座装配，Web 运行时天然继承并透传四维上下文）
    async:
      enabled: true               # 是否启用受管异步线程池（默认 true）
      core-pool-size: 8           # 核心线程数（默认可用 CPU 核心数）
      max-pool-size: 16           # 最大线程数（默认可用 CPU 核心数 * 2）
      queue-capacity: 1000        # 阻塞队列容量（默认 1000）
      keep-alive-seconds: 60      # 空闲线程存活时间（秒，默认 60）
      thread-name-prefix: "ateng-async-" # 异步工作线程名前缀（默认 ateng-async-）
      wait-for-tasks-to-complete-on-shutdown: true # 容器关闭时等待任务执行完成（默认 true）
      await-termination-seconds: 60 # 优雅停机等待超时时间（秒，默认 60）
```
