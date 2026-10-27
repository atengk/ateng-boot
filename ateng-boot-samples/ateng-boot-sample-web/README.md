# ateng-boot-sample-web Web 运行时契约与全链路追踪场景自验工程

> 专一 `ateng-boot-web` Web 契约层与全链路追踪自验：显式强类型契约、分层参数校验防卫、因果异常脱敏、双向加签验签、自适应限流防抖、操作与接口审计、流式下载与云原生优雅停机治理。

---

## 1. 场景痛点与功能定位

`ateng-boot-web` 是面向生产就绪的 Web 运行时基底，坚守显式强类型契约与零实体泄露原则。`ateng-boot-sample-web` 专注于纯净、端到端的 HTTP 协议边界与管道自验：
- **纯内存零 DB 依赖（Zero Database Dependency）**：零关系型数据库，完全依托内存存储与 Mock 环境自洽运行；
- **显式契约与第一道防线校验**：坚决杜绝 `@ResponseBodyAdvice` 隐式拦截包装，验证 `Result<T>`、`ValidList<T>` 批量校验、分组校验、Long 精度防失真与时间标准化；
- **全链路追踪与运行时观测**：验证 `TraceFilter` & `TraceUtil` MDC 链路流转、`SlowRequestFilter` 慢请求性能主动告警与长连接放行、虚拟线程执行探查；
- **双向数据安全与加验签防重放**：验证 `@ApiSign` HMAC-SHA256 对称加签、RSA 非对称加签与国密 SM2/SM3 非对称加签、Query/Body 报文防篡改与 Nonce 防重放；
- **高可用流量治理**：验证 `@RateLimiter` 接口/IP/用户/租户多维限流与 `@Idempotent` SpEL 防重幂等、按钮防抖与异常重试；
- **结构化审计与环境安全**：验证 `@OperateLog` 操作审计出参动态脱敏与敏感参数排除、`OperateLogStorageProvider` SPI 内存收集、`IpUtil` 真实 IP 穿透与 RFC 6266 双重文件名流式下载。

---

## 2. 场景控制器架构与代码全景

工程内聚五大场景控制器与专职 SPI 实现，100% 覆盖 Web 运行时核心门面与特性：

| 场景控制器 / 组件 | 覆盖核心门面与注解 | 对应端到端测试 |
| :--- | :--- | :--- |
| `WebContractSampleController` | `Result<T>`, `ValidList<T>`, `ValidationGroups`, `GlobalExceptionHandler`, `BusinessException`, `JacksonCustomizer` | `WebContractSampleControllerTest` |
| `WebSecuritySignSampleController` | `@ApiSign`, `ApiSigner`, `HmacSha256Signer`, `RsaSigner`, `Sm2Signer`, `SignCanonicalUtil`, `CachedBodyHttpServletRequest` | `WebSecuritySignSampleControllerTest`, `WebSecuritySignRsaSampleTest`, `WebSecuritySignSm2SampleTest` |
| `WebTrafficSampleController` | `@RateLimiter`, `LimitType`, `InMemoryRateLimiterStore`, `@Idempotent`, `InMemoryIdempotentStore` | `WebTrafficSampleControllerTest` |
| `WebAuditSampleController` | `@OperateLog`, `OperateLogAspect`, `OperateLogStorageProvider`, `@WebLog`, `@FieldSensitive` | `WebAuditSampleControllerTest` |
| `WebRuntimePipelineSampleController` | `TraceFilter`, `TraceUtil`, `ContentCachingFilter`, `SlowRequestFilter`, `SecurityHeadersFilter`, `ShallowEtagHeaderFilter`, `IpUtil`, `ServletUtil`, `/pre-stop` | `WebRuntimePipelineSampleControllerTest`, `WebSecurityHeadersAndEtagSampleTest` |
| `WebXssSampleController` | `XssFilter`, `XssCleaner`, `XssStringDeserializer`, `@XssIgnore`, `XssHttpServletRequestWrapper` | `WebXssSampleControllerTest`, `WebXssStripModeSampleTest` |
| `MemoryOperateLogStorageProvider` | `OperateLogStorageProvider` SPI 内存解耦实现 | 注入自验上下文 |

---

## 3. 核心场景实测代码范式

### 1. 显式契约与批量校验 (`ValidList` & `Result<T>`)
```java
@PostMapping("/users/batch")
public Result<Integer> batchCreateUsers(@Valid @RequestBody ValidList<UserRegisterDTO> users) {
    return Result.success(users.size());
}
```
当集合中任意元素的手机号格式不合规时，快速失败并返回友好提示：
```json
{
  "code": 400,
  "msg": "手机号格式不正确",
  "data": null,
  "success": false,
  "traceId": "c8a14b0365734fb4864f7bdf753b890a"
}
```

### 2. 双向 API 加签验签与防重放 (`@ApiSign`)
```java
// 1. 标准下单业务接口（支持 HMAC-SHA256、RSA）
@PostMapping("/orders")
@ApiSign(verifyRequest = true, signResponse = true, expireSeconds = 10)
public Result<String> createOrder(@Valid @RequestBody OrderSignDTO dto) {
    return Result.success("ORD_SIGNED_" + dto.getOrderNo());
}

// 2. 国密 SM2/SM3 数字签名接口（政企信创高合规场景，纯 JDK 17 原生实现）
@PostMapping("/sm2")
@ApiSign(verifyRequest = true, signResponse = true, expireSeconds = 10)
public Result<String> createOrderSm2(@Valid @RequestBody OrderSignDTO dto) {
    return Result.success("SM2_SIGNED_" + dto.getOrderNo());
}
```
客户端携带 `X-App-Id`、`X-Timestamp`、`X-Nonce`、`X-Signature` 发送请求；服务端验签通过后，在响应头回传 `X-Signature`。若重复提交同一 Nonce 或报文被篡改，立即拦截阻断：
```json
{
  "code": 40003,
  "msg": "请求已被处理或存在重放风险",
  "data": null,
  "success": false,
  "traceId": "edf29c42e3ec4dfdfcabb67e39c1a67b"
}
```

### 3. 操作审计与出参动态脱敏 (`@OperateLog` & SPI)
```java
@PostMapping("/user")
@OperateLog(
    module = "用户管理",
    type = OperateTypeEnum.INSERT,
    description = "新增系统用户",
    excludeParams = {"password"}
)
public Result<UserRegisterVO> createUser(@RequestBody AuditUserDTO dto) {
    UserRegisterVO vo = UserRegisterVO.builder()
            .id(1846182948192841920L)
            .name(dto.getUsername())
            .age(25)
            .email(dto.getEmail() != null ? dto.getEmail() : "user@example.com")
            .phone(dto.getPhone() != null ? dto.getPhone() : "13812345678")
            .build();
    return Result.success(vo);
}
```
- **入参敏感字段自动排除**：`operParams` 记录为 `{"username":"ateng","password":"[EXCLUDED]"}`；
- **出参安全动态掩码**：`jsonResult` 记录为 `{"id":"1846182948192841920","name":"ateng","age":25,"email":"user@example.com","phone":"138****5678"}`；
- **内存真明文守护**：Controller 与内存实体中手机号 100% 保持原始明文 `13812345678`。

### 4. HTTP 安全响应头防御与 ETag 协商缓存 (`SecurityHeadersFilter` & `ShallowEtagHeaderFilter`)
```java
// 1. 字典/静态配置数据端点（支持 ETag 304 协商缓存）
@GetMapping("/cached-dict")
public Result<Map<String, String>> getCachedDict() {
    Result<Map<String, String>> result = Result.success(Map.of("status", "ACTIVE", "locale", "zh_CN", "app", "ateng-boot-sample-web"));
    result.setTimestamp(1700000000000L);
    return result;
}

// 2. 业务自定义响应头（验证 containsHeader 守卫防覆盖机制）
@GetMapping("/custom-security-headers")
public Result<String> getCustomSecurityHeaders(HttpServletResponse response) {
    response.setHeader("X-Frame-Options", "DENY"); // 保持 DENY，不被 SAMEORIGIN 覆盖
    return Result.success("custom-headers-ok");
}
```
- **等保三级基线防御**：默认自动注入 `X-Content-Type-Options: nosniff`、`X-Frame-Options: SAMEORIGIN`、`X-XSS-Protection: 1; mode=block` 与 `Referrer-Policy: strict-origin-when-cross-origin`；
- **容器指纹剥离**：彻底过滤与消除 `Server` 响应头（如 Tomcat 特征）；
- **ETag 协商缓存**：开启 `ateng.boot.web.etag.enabled=true` 时，二次携带 `If-None-Match` 返回 `304 Not Modified` 且响应体为空，显著节省带宽。

### 5. XSS 跨站脚本双轨无害化清洗与字段级富文本豁免 (`XssFilter` & `@XssIgnore`)
```java
// 1. 混合 DTO 接收：普通字段自动清洗，@XssIgnore 标注字段安全豁免放行
@PostMapping("/article")
public Result<ArticlePublishDTO> publishArticle(@Valid @RequestBody ArticlePublishDTO dto) {
    return Result.success(dto);
}

// 2. 类级别整实体豁免放行
@PostMapping("/announcement")
public Result<AnnouncementPublishDTO> publishAnnouncement(@Valid @RequestBody AnnouncementPublishDTO dto) {
    return Result.success(dto);
}
```
- **双轨清洗**：支持 `XssMode.ESCAPE`（默认 HTML 转义）与 `XssMode.STRIP`（标签剔除），同时覆盖 Query、Form 表单与 JSON 请求体；
- **精准豁免**：富文本正文标注 `@XssIgnore` 杜绝误伤合法 HTML 业务标签；
- **路径白名单**：配置 `exclude-urls`（如 `/api/sample/xss/excluded/**`）全局放行特定管理发布端点。

---

## 4. 定向集成测试准出

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-web -am -T 1C -nsu -B
```

所有场景测试基于 `MockMvc` 端到端全自洽执行，零物理网络与数据库依赖：
```
[INFO] Results:
[INFO] 
[INFO] Tests run: 54, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```
