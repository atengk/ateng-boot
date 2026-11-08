# ateng-boot-satoken 安全鉴权与分布式会话治理套件

> 面向生产就绪的 Sa-Token 企业级安全基础设施与分布式会话治理底座，提供轻量无侵入鉴权、强类型 `LoginUser` / `LoginHelper` 载荷模型、双轨拦截防御体系、Core / MyBatis-Plus 审计全链路贯通、条带锁两级权限缓存与动态热刷新、多账号独立体系会话物理隔离、客户端 IP 自动嗅探双轨防爆破熔断、微服务内部零信任 Same-Token 出站自动透传、在线会话安全游标监控与精准下线治理、数据库驱动动态 URL 路径权限匹配 SPI、Spring 原生安全事件总线及 OpenAPI / Knife4j 鉴权弹窗无侵入自动化集成。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **会话登录** | 强类型用户登录 | `LoginHelper` | `ateng.boot.satoken.enabled`<br>`ateng.boot.satoken.token.*` | `login(LoginUser)` / `login(LoginUser, String)` / `login(LoginUser, SaLoginParameter)` / `login(LoginUser, String, String)` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **会话注销** | 会话安全注销 | `LoginHelper` | - | `logout()` / `logout(String)` / `logoutByTokenValue(String)` / `logoutByDevice(String)` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **设备治理** | 账号踢人与顶替下线 | `LoginHelper` | - | `kickout(Object loginId)` / `kickout(Object, String)` / `kickoutByToken(String)` / `kickoutDevice(Object, String)` / `kickoutByDevice(Object, String)` / `replaced(Object)` / `replaced(Object, String)` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **账号封禁** | 账号/服务封禁与解封 | `LoginHelper` | - | `disable(Object, Duration)` / `disable(Object, String, Duration)` / `untieDisable(Object)` / `untieDisable(Object, String)` / `isDisable` / `checkDisable` / `getDisableTime` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **二级认证** | 编程式与声明式二级认证 | `LoginHelper`<br>`@SaCheckSafe` | - | `openSafe(Duration)` / `openSafe(int)` / `openSafe(String, Duration)` / `isSafe()` / `checkSafe()` / `closeSafe()` / `@SaCheckSafe` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **状态查询** | 登录状态与令牌查询 | `LoginHelper` | - | `isLogin()` / `isLogin(String)` / `checkLogin()` / `checkLogin(String)` / `getTokenValue()` / `getTokenValue(String)` / `getTokenValueByLoginId(Object)` / `getTokenTimeout()` / `getSessionTimeout()` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **载荷存取** | 强类型实体存取 | `LoginHelper` | - | `setLoginUser(LoginUser)` / `setLoginUser(LoginUser, String)` / `getLoginUser()` / `getLoginUser(String)` / `getLoginUser(Class<T>)` / `getLoginUser(Class<T>, String)` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **身份感知** | 用户/租户身份提取 | `LoginHelper` | - | `getUserId()` / `getUserId(String)` / `getUserIdAsLong()` / `getRealUserId()` / `getUserAccount()` / `getUserName()` / `getTenantId()` / `getDeptId()` / `buildCompositeLoginId` (透明对称剥离租户前缀) | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **多账号体系** | 独立账号体系与物理隔离 | `LoginHelper` | `ateng.boot.satoken.primary-login-type` | `getStpLogic(String loginType)` / `setPrimaryLoginType(String)` / `getPrimaryLoginType()` / `registerStpLogic(String, StpLogic)`，独立体系 Token 请求头与 Session 物理隔离 | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **会话切换** | 跨请求会话模拟与还原 | `LoginHelper` | - | `switchTo(String)` / `switchTo(Long)` / `endSwitch()` 会话级模拟，保留原始运维人追踪链路 | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **瞬态模拟** | 瞬态代码块安全模拟 | `LoginHelper` | - | `runAs(String, Supplier<T>)` / `runAs(String, Runnable)` / `runAs(Long, ...)` 栈式保护与 `finally` 可靠清理 | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **双维审计** | 模拟感知与原始人追踪 | `LoginHelper`<br>`SaTokenCurrentUserProvider` | - | `isRunAs()` / `getOriginalUserId()`，自动贯通 Core/Web `@OperateLog` 切面与操作审计双维追溯 | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **越权防御** | 数据所有权防卫断言 | `@CheckDataOwner`<br>`DataOwnerProvider`<br>`LoginHelper` | `ateng.boot.satoken.data-owner.enabled` | `@CheckDataOwner(expr, provider, allowAdmin, message)` / `DataOwnerProvider.getOwnerId` / `checkSelf(String/Long)` / `isSelf(String/Long)` / `isSelfOrAdmin` / `checkBelongsToTenant` / `isBelongsToTenant` | `SaCheckDataOwnerAspectTest` / `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **公开访问** | 声明式公开与动态白名单 | `@Anonymous`<br>`SaTokenWhitelistProvider`<br>`AnonymousUrlProcessor` | `ateng.boot.satoken.white-list`<br>`ateng.boot.satoken.interceptor.excludes` | 类/方法级 `@Anonymous`，`SaTokenWhitelistProvider.getWhitelist()`，动态扫描转译 AntPath，多源白名单聚合放行 | `AnonymousUrlProcessorTest` / `SampleSaTokenIntegrationTest` |
| **权限断言** | 角色与特权判定 | `LoginHelper` | - | `getRoles()` / `getPermissions()` / `hasRole(String)` / `hasPermission(String)` / `isSuperAdmin()` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **用户载荷** | 工业级强类型载荷 | `LoginUser` | - | 标配 `@SaJsonType`、链式 setter 与 `extraData` 扩展字典 | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **路由拦截** | 双轨路由拦截与白名单 | `AtengSaTokenInterceptor`<br>`SaTokenWebMvcConfigurer` | `ateng.boot.satoken.interceptor.*`<br>`ateng.boot.satoken.white-list` | `isAnnotation(false)` 专精路径白名单防漏，`preHandle` 注入四维上下文，`afterCompletion` 强制对称清空 | `AtengSaTokenInterceptorTest` / `SampleSaTokenIntegrationTest` |
| **注解鉴权** | 细粒度声明式鉴权 | `@SaCheckLogin`<br>`@SaCheckRole`<br>`@SaCheckPermission`<br>`@SaCheckSafe`<br>`@SaCheckDisable` | - | AOP 切面统一承接 Controller 与 Service 边界方法鉴权，彻底消除双重校验损耗 | `SampleSaTokenIntegrationTest` |
| **内部零信任** | 微服务内部防伪装调用 | `@CheckSameToken`<br>`SaCheckSameTokenAspect` | `ateng.boot.satoken.same-token.enabled`<br>`ateng.boot.satoken.same-token.header-name`<br>`ateng.boot.satoken.same-token.token-value` | 校验内网微服务调用凭据，防网关绕过探测；默认请求头 `SA-SAME-TOKEN`，支持固定密钥与 MessageDigest.isEqual 常数时间安全比对 | `SaCheckSameTokenAspectTest` / `SampleSaTokenIntegrationTest` |
| **出站自动透传** | 客户端出站拦截器自动装配 | `SameTokenClientHttpRequestInterceptor`<br>`RestClientCustomizer`<br>`RestTemplateCustomizer` | `ateng.boot.satoken.same-token.auto-inject-client` | 自动为 Spring 受管 RestClient/RestTemplate 注入拦截器，跨微服务发起请求时透明打标 `SA-SAME-TOKEN` | `SameTokenClientConfigurationTest` / `SampleSaTokenIntegrationTest` |
| **两级缓存** | 条带锁防击穿权限缓存 | `CachedStpInterface`<br>`PermissionCacheService` | `ateng.boot.satoken.permission-cache.*` | 基于 `TwoLevelCacheManager` 实现条带锁并发防击穿、Caffeine L1 纳秒微缓存、Redis L2 分布式缓存、空哨兵与 10%~20% 动态 Jitter，多账号 `loginType` 细粒度穿透，无 Redis 时自动纯内存平滑降级 | `CachedStpInterfaceTest` / `PermissionCacheServiceTest` / `SampleSaTokenIntegrationTest` |
| **业务 SPI** | 角色与权限业务加载器 | `UserRolePermissionLoader` | - | `loadRolesByUserId(String, String)` / `loadPermissionsByRole(String, String)` / `loadDirectPermissionsByUserId(String, String)` 支持多账号体系 | `CachedStpInterfaceTest` / `SampleSaTokenIntegrationTest` |
| **用户上下文 SPI** | 当前操作人身份适配 | `SaTokenCurrentUserProvider` | - | 实现 Core `CurrentUserProvider` SPI，向底层贯通当前登录用户与模拟人，驱动 MyBatis-Plus 审计自动填充 | `SampleSaTokenIntegrationTest` |
| **租户上下文 SPI** | 当前租户上下文适配 | `SaTokenCurrentTenantProvider` | - | 实现 Core `CurrentTenantProvider` SPI，向底层贯通当前租户标识，驱动多租户行级数据隔离 | `SampleSaTokenIntegrationTest` |
| **事件总线** | 原生安全事件广播 | `SaTokenEventPublisher` | - | 桥接广播 `SaLoginEvent`、`SaLogoutEvent`、`SaKickoutEvent`、`SaReplacedEvent`、`SaDisableEvent` 等 Spring 原生事件 | `SaTokenEventPublisherTest` / `SampleSaTokenIntegrationTest` |
| **OpenAPI 适配** | Knife4j 鉴权弹窗自动化 | `SaTokenOpenApiCustomizer` | `ateng.boot.satoken.openapi.enabled`<br>`ateng.boot.satoken.openapi.token-name` | 自动在 `/v3/api-docs` 与 Knife4j 界面右上角挂载 Authorize 鉴权弹窗 | `SaTokenOpenApiCustomizerTest` / `SampleSaTokenIntegrationTest` |
| **分布式存储** | 纯净 JSON 会话存储驱动 | `SaTokenRedisDao` | - | 基于 `StringRedisTemplate` 存储，杜绝 `@class` 反序列化白名单漏洞，无 Redis 自动平滑降级 | `SaTokenRedisDaoTest` / `SampleSaTokenIntegrationTest` |
| **集群同步** | 分布式集群即时同步与广播作废 | `SaTokenClusterBroadcaster`<br>`SaTokenClusterSyncListener` | `ateng.boot.satoken.cluster-sync.enabled`<br>`ateng.boot.satoken.cluster-sync.channel` | `broadcast(eventType, targetKey)` / `getInstanceId()` / `processRemoteMessage(msg)` / 自消费防卫丢弃与异地本地缓存精准失效 | `SaTokenClusterSyncTest` / `SampleSaTokenIntegrationTest` |
| **防暴力破解** | 双轨防暴力破解与 IP 嗅探 | `LoginFailureLockService` | `ateng.boot.satoken.login-defense.*` | `recordFailure(account, clientIp)` / `recordIpFailure(clientIp)` / `sniffClientIp()` / `resolveClientIp(clientIp)` / `isIpLocked(clientIp)` / `checkIpLocked(clientIp)` / `isIpWhiteListed(clientIp)` / `clearAllFailure(account, clientIp)`，支持 IPv4/IPv6/CIDR 白名单与账号+IP 双轨熔断 | `LoginFailureLockServiceTest` / `SampleSaTokenIntegrationTest` |
| **在线会话** | 在线会话监控与增量游标治理 | `LoginHelper`<br>`OnlineUser` | - | `searchOnlineUserPage(keyword, page, size)` / `searchOnlineUsers(keyword, page, size)` / `getOnlineUsers(page, size, keyword)` / `getOnlineUserCount(loginType)`，采用增量游标 SCAN 检索与分页切片，防内存 OOM | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **动态权限** | 动态 URL 路径权限匹配 SPI | `DynamicPathPermissionProvider`<br>`PathPermissionRule` | `ateng.boot.satoken.interceptor.enabled` | `DynamicPathPermissionProvider.getPathPermissionRules()`，`AtengSaTokenInterceptor` 自动挂载管道，按 AntPath + HTTP Method 动态执行权限鉴权 | `AtengSaTokenInterceptorTest` / `SampleSaTokenIntegrationTest` |
| **异常转译** | 统一显式 Result 响应 | `SaTokenExceptionHandler` | - | 将未登录 (401)、权限不足 (403)、服务封禁 (403)、Same-Token 无效 (403) 等转译为 HTTP 200 + `Result<Void>` 显式契约 | `SampleSaTokenIntegrationTest` |

---

## 1. 模块定位与极速起步 (Happy Path & Out-of-the-Box)

`ateng-boot-satoken` 深度融合 Sa-Token `1.44.0` 核心能力，为 Spring Boot 3 应用提供工业级安全基础设施与分布式会话治理底座：
- **显式统一契约**：严格遵循显式强类型契约（[ADR-0002](../docs/adr/0002-explicit-result-contract-no-response-body-advice.md)），将全量鉴权异常统一转译为 HTTP 200 + `Result<T>` 格式，绝不泄露内部调用堆栈；
- **零臃肿与自适应平滑降级**：恪守零重型依赖（[ADR-0011](../docs/adr/0011-zero-bloat-and-co-located-utilities.md)），有 Redis 时无缝接入纯净 JSON 分布式存储（[ADR-0022](../docs/adr/0022-dual-core-redis-and-distributed-coordination-architecture.md)），无 Redis 或 `ateng.boot.redis.enabled: false` 时 100% 自动平滑降级为纯内存会话存储（Zero-Mock Invariant）；
- **全链路生态贯通**：深度融合 Core 模块 `CurrentUserProvider` / `CurrentTenantProvider` SPI（[ADR-0024](../docs/adr/0024-enterprise-satoken-authentication-and-security-architecture.md)），全自动贯通持久层审计填充与行级多租户数据隔离。

### 1.1 Maven 依赖引入

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-satoken</artifactId>
</dependency>
```

### 1.2 最小可用配置 (application.yml)

开箱即用，无需配置 Redis 即可在单机内存中极速起步：

```yaml
ateng:
  boot:
    satoken:
      enabled: true
      token:
        timeout: 24h        # Token 绝对有效期，强类型 Duration (默认: 24h)
        active-timeout: 30m # 活跃空闲超时，强类型 Duration (默认: 30m)
```

### 1.3 极速起步实战代码

直接通过 `LoginHelper` 签发与获取强类型主体，出参显式声明 `Result<T>`：

```java
@RestController
@RequestMapping("/api/auth")
public class QuickStartAuthController {

    @PostMapping("/login")
    public Result<String> login(@RequestBody LoginRequestDTO req) {
        LoginUser user = LoginUser.builder()
                .userId(req.getUserId())
                .userName(req.getUsername())
                .tenantId(req.getTenantId())
                .roles(Set.of("admin"))
                .permissions(Set.of("order:query"))
                .build();
        LoginHelper.login(user);
        return Result.success(LoginHelper.getTokenValue());
    }

    @GetMapping("/me")
    public Result<LoginUser> getMe() {
        LoginHelper.checkLogin();
        return Result.success(LoginHelper.getLoginUser());
    }
}
```

---

## 2. 核心架构与设计不变量 (Core Architecture & Invariants)

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                             外部 HTTP 请求 / 微服务内部 RPC                        │
└────────────────────────────────────────┬─────────────────────────────────────────┘
                                         │
        ┌────────────────────────────────┴────────────────────────────────┐
        ▼                                                                 ▼
┌─────────────────────────────────┐                     ┌──────────────────────────────────┐
│  SaServletFilter / Interceptor  │                     │     SaAnnotationAspect (AOP)     │
│  (路由匹配、白名单、动态路径SPI)  │                     │  (方法鉴权 / SameToken / 数据防越权)│
└────────────────┬────────────────┘                     └────────────────┬─────────────────┘
                 │                                                       │
                 └───────────────────────┬───────────────────────────────┘
                                         ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                     ateng-boot-satoken 核心引擎与安全门面                          │
├────────────────────────────────┬─────────────────────────────────────────────────┤
│ • LoginHelper (强类型多账号门面) │ • CachedStpInterface (L1微缓存 + L2 Redis条带锁) │
│ • LoginUser (工业级用户载荷模型) │ • DynamicPathPermissionProvider (动态URL权限SPI)│
│ • LoginFailureLockService 防爆破│ • SameTokenClientHttpRequestInterceptor 出站透传│
│ • OnlineUser (在线会话安全游标) │ • SaTokenClusterBroadcaster (集群即时同步广播)   │
│ • SaTokenCurrentUserProvider   │ • SaTokenEventPublisher (桥接 Spring 原生事件)  │
│ • @CheckSameToken (内部零信任)  │ • SaTokenOpenApiCustomizer (Knife4j 弹窗自动化) │
└────────────────┬───────────────┴───────────────────────────────┬─────────────────┘
                 │                                               │
                 ▼                                               ▼
┌─────────────────────────────────┐             ┌──────────────────────────────────┐
│    持久层与审计生态联动           │             │     自适应存储驱动与降级引擎       │
├─────────────────────────────────┤             ├──────────────────────────────────┤
│ Core: CurrentUserProvider SPI   │             │ 有 Redis: SaTokenRedisDao (纯净) │
│ MyBatis-Plus: createBy/updateBy │             │ 无 Redis: SaTokenDaoDefault (内存)│
└─────────────────────────────────┘             └──────────────────────────────────┘
```

### 核心设计六道不变量 (The 6 Invariants)

1. **显式响应契约 (Explicit Result Contract)**：全域安全拦截器与切面鉴权异常统一由 `SaTokenExceptionHandler` 转译为 HTTP 200 + 401/403 `Result<Void>` 显式契约，绝不依赖 `@ResponseBodyAdvice` 全局包装（[ADR-0002](../docs/adr/0002-explicit-result-contract-no-response-body-advice.md)）；
2. **严格无状态与并发安全 (Statelessness & Concurrency Safety)**：公共单例组件（`LoginHelper`、`PermissionCacheService`、`AtengSaTokenInterceptor`）保持严格无状态，无任何实例变量共享可变业务状态；
3. **内存真明文不变量 (In-Memory Plaintext Invariant)**：会话中持有的 `LoginUser` 载荷与用户信息在内存中始终保持 100% 原始真实明文，脱敏收敛于视图层序列化（[ADR-0012](../docs/adr/0012-lifecycle-separated-data-security.md)）；
4. **四维上下文全链路透传与对称清理 (4D Context Propagation & Symmetric Lifecycle)**：请求经由 `AtengSaTokenInterceptor.preHandle` 注入 $\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$，在 `afterCompletion` 中无条件执行双清空，彻底杜绝工作线程复用引发的上下文串染；
5. **纯内存确定性平滑降级 (Zero-Mock Invariant)**：当未配置 Redis 或 `ateng.boot.redis.enabled: false` 时，自动平滑降级为 `SaTokenDaoDefault` 内存存储与单机降级逻辑，100% 离线自洽，无需启动外部中间件；
6. **防御性自动装配四道铁律 (Defensive Autoconfiguration)**：所有自动装配类统一使用 `@AutoConfiguration` 标注并注册于 Spring Boot 3 标准 imports 文件，标配条件守卫与 `@ConditionalOnMissingBean`。

---

## 3. 会话认证、多租户上下文与多账号体系治理

### 场景痛点
1. **类型安全性缺失**：原生 `StpUtil.login(id)` 仅存取简单数字/字符串标量，读取用户姓名、租户、角色权限需繁琐反查数据库或进行易出错的 `Map` 强转；
2. **多租户主键串染**：SaaS 场景同一租户与跨租户用户 ID 容易冲突，复合主键在业务层往往需要手工拆分处理；
3. **多端多设备顶替踩踏**：管理后台（`admin`）与 C 端会员（`member`）共用 Token Header 导致登录踩踏与凭据污染；
4. **线程复用串染**：Web 容器线程复用导致未及时清理的 ThreadLocal 残留上一用户的操作凭据与租户上下文。

### 3.1 专属 YAML 配置片段

```yaml
ateng:
  boot:
    satoken:
      enabled: true               # 是否开启 Sa-Token 增强套件 (默认: true)
      primary-login-type: login   # 系统默认主账号体系类型标识 (默认: login)
      token:
        timeout: 24h              # Token 绝对有效期，强类型 Duration (默认: 24h)
        active-timeout: 30m       # 活跃空闲超时，强类型 Duration (默认: 30m)
        is-concurrent: true       # 同一账号是否允许并发登录 (默认: true)
        is-share: true            # 在多人登录同一账号时，是否共用同一个 Token (默认: true)
        max-login-count: 12       # 同一账号最大并发登录终端数 (默认: 12)
        token-style: uuid         # Token 算法生成风格 (默认: uuid)
      interceptor:
        enabled: true             # 是否挂载受管双轨拦截器 (默认: true)
        check-login: false        # 是否开启全局路由强制登录校验 (默认: false)
        excludes:                 # 业务定制免登录白名单路径
          - "/sample/satoken/auth/login"
          - "/doc.html"
          - "/v3/api-docs/**"
```

### 3.2 面向实战的业务 Java 示例（源自 `AuthSessionController.java`）

```java
@Validated
@RestController
@RequestMapping("/sample/satoken/auth")
@RequiredArgsConstructor
public class AuthSessionController {

    private final SaTokenSampleService sampleService;

    /**
     * 用户登录：支持多端设备与多租户复合身份签发
     */
    @PostMapping("/login")
    public Result<String> login(@RequestBody LoginRequestDTO request) {
        String userId = (request != null && request.getUserId() != null && !request.getUserId().isBlank())
                ? request.getUserId() : "user-1001";
        String username = (request != null && request.getUsername() != null && !request.getUsername().isBlank())
                ? request.getUsername() : userId;

        LoginUser loginUser = LoginUser.builder()
                .userId(userId)
                .userAccount(username)
                .userName(username)
                .tenantId(request != null ? request.getTenantId() : null)
                .roles(request != null ? request.getRoles() : null)
                .permissions(request != null ? request.getPermissions() : null)
                .build();

        if (request != null && request.getDevice() != null && !request.getDevice().isBlank()) {
            LoginHelper.login(loginUser, request.getDevice());
        } else {
            LoginHelper.login(loginUser);
        }
        return Result.success(LoginHelper.getTokenValue());
    }

    /**
     * 获取当前登录主体详情
     */
    @GetMapping("/me")
    public Result<LoginUser> getLoginUser() {
        LoginHelper.checkLogin();
        return Result.success(LoginHelper.getLoginUser());
    }

    /**
     * 获取当前操作人用户 ID（透明对称剥离多租户前缀）
     */
    @GetMapping("/user-id")
    public Result<String> getUserId() {
        LoginHelper.checkLogin();
        return Result.success(LoginHelper.getUserId());
    }

    /**
     * 获取当前租户上下文标识
     */
    @GetMapping("/current-tenant")
    public Result<String> getCurrentTenant() {
        LoginHelper.checkLogin();
        return Result.success(TenantContextHolder.getTenantId());
    }

    /**
     * 校验登录状态
     */
    @GetMapping("/check-login")
    public Result<String> checkLogin() {
        LoginHelper.checkLogin();
        return Result.success("已登录");
    }

    /**
     * 创建租户采购订单（验证当前操作人与租户上下文自动审计填充）
     */
    @PostMapping("/orders")
    public Result<Long> createOrder(@Valid @RequestBody OrderCreateDTO orderDto) {
        Long orderId = sampleService.createOrder(orderDto.getOrderNo(), orderDto.getAmount());
        return Result.success(orderId);
    }

    /**
     * 查询租户订单详情（验证零实体泄露原则，返回强类型 VO）
     */
    @GetMapping("/orders/{orderId}")
    public Result<TenantOrderVO> getOrder(@PathVariable("orderId") Long orderId) {
        TenantOrderVO order = sampleService.getOrderById(orderId);
        if (order == null) {
            return Result.fail(CommonResultCode.NOT_FOUND.getCode(), "订单不存在");
        }
        return Result.success(order);
    }

    /**
     * 指定设备端注销下线
     */
    @PostMapping("/logout-device")
    public Result<Void> logoutDevice(@RequestParam("device") String device) {
        LoginHelper.logoutByDevice(device);
        return Result.success();
    }

    /**
     * 当前会话注销下线
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        LoginHelper.logout();
        return Result.success();
    }
}
```

### 3.3 核心机制与生产边界说明

1. **多租户复合主键透明剥离**：
   - 当多租户开启时，底层会话将以 `{tenantId}_{userId}` 作为物理复合主键存储，保障跨租户物理隔离；
   - 业务调用 `LoginHelper.getUserId()` 或 `getUserIdAsLong()` 时，框架通过 `buildCompositeLoginId` 对称逆算法，**全自动透明剥离租户前缀**，始终向业务层返回纯净的原始业务用户 ID，杜绝业务层手工截取字符引发的空指针或数组越界；
2. **多账号体系物理隔离治理**：
   - 系统支持管理后台、C 端会员、开放应用等独立账号体系并行运作；
   - 非主体系自动采用独立 Token 请求头（如 `satoken-admin`、`satoken-member`），会话存储物理隔离；所有 `LoginHelper` API 均支持重载显式传递 `loginType`，确保互不踩踏；
3. **双轨拦截与四维上下文对称生命周期**：
   - `AtengSaTokenInterceptor.preHandle`：在路由放行前，自动从当前会话提取用户载荷与租户信息，镜像注入 Core 模块的 `UserContextHolder` 与 `TenantContextHolder`；
   - `AtengSaTokenInterceptor.afterCompletion`：在 HTTP 响应渲染完成后的 `finally` 阶段，强制执行 `UserContextHolder.clear()` 与 `TenantContextHolder.clear()`，彻底杜绝容器工作线程复用引发的上下文串染与数据泄露；
4. **MyBatis-Plus 自动审计零侵入贯通**：
   - 自动装配 `SaTokenCurrentUserProvider` 与 `SaTokenCurrentTenantProvider`，分别实现 Core 模块的 `CurrentUserProvider` 与 `CurrentTenantProvider` SPI；
   - 实体类继承 `BaseEntity` 时，无需业务手工赋值，底层在 `INSERT` / `UPDATE` 时全自动回填 `create_by`、`update_by`、`create_time` 与 `update_time`。

---

## 4. 细粒度访问控制：RBAC、二级认证与水平越权防御 (IDOR)

### 场景痛点
1. **鉴权层次割裂**：仅在网关层做粗粒度 URL 校验，无法对内部 Service 复杂业务流实施方法级鉴权；
2. **高危操作缺乏二级防护**：大额资金划拨、敏感密码修改等高危操作未做时效性二次验证，凭据一旦失窃即直接引发灾难；
3. **水平越权 (IDOR) 泛滥**：修改个人信息或取消订单时，恶意用户只需篡改请求体或 URL 中的 `userId` / `orderId`，即可越权操作他人资源；
4. **鉴权异常非标**：鉴权失败时底层抛出运行时异常，导致非标 500 或泄露服务器敏感堆栈。

### 4.1 专属 YAML 配置片段

```yaml
ateng:
  boot:
    satoken:
      data-owner:
        enabled: true   # 是否开启 @CheckDataOwner 数据所有权防水平越权切面 (默认: true)
```

### 4.2 面向实战的业务 Java 示例（源自 `AccessControlController.java`）

```java
@Validated
@RestController
@RequestMapping("/sample/satoken/access")
@RequiredArgsConstructor
public class AccessControlController {

    private final SaTokenSampleService sampleService;

    /**
     * 多角色 OR 逻辑校验（需具备 audit_manager 或 finance_director 角色）
     */
    @GetMapping("/role-audit")
    @SaCheckRole(value = {"audit_manager", "finance_director"}, mode = SaMode.OR)
    public Result<String> checkRoleAudit() {
        return Result.success("审计角色鉴权通过");
    }

    /**
     * 细粒度权限码校验（需要 order:query 权限）
     */
    @GetMapping("/perm-order-query")
    @SaCheckPermission("order:query")
    public Result<String> checkPermOrderQuery() {
        return Result.success("订单查询权限鉴权通过");
    }

    /**
     * 穿透至 Service 层执行声明式权限注解校验
     */
    @GetMapping("/service-permission")
    public Result<String> checkServicePermission() {
        return Result.success(sampleService.checkServicePermission());
    }

    /**
     * 开启二级安全认证（有效期 120 秒）
     */
    @PostMapping("/open-safe")
    public Result<Integer> openSafe(@RequestParam(value = "timeout", defaultValue = "120") int timeout) {
        LoginHelper.openSafe(timeout);
        return Result.success(timeout);
    }

    /**
     * 关闭二级安全认证时间窗
     */
    @PostMapping("/close-safe")
    public Result<Void> closeSafe() {
        LoginHelper.closeSafe();
        return Result.success();
    }

    /**
     * 编程式二级认证大额资金结算
     */
    @PostMapping("/safe-settle")
    public Result<String> safeSettleProgrammatic(@Valid @RequestBody SettlementRequestDTO dto) {
        LoginHelper.checkSafe();
        return Result.success("大额资金结算已完成: " + dto.getAmount());
    }

    /**
     * 声明式 @SaCheckSafe 二级认证大额资金结算
     */
    @PostMapping("/safe-settle-anno")
    @SaCheckSafe
    public Result<String> safeSettleDeclarative(@Valid @RequestBody SettlementRequestDTO dto) {
        return Result.success("大额资金结算已完成 (声明式): " + dto.getAmount());
    }

    /**
     * 基于 @CheckDataOwner 的用户个人资料防水平越权修改（直接匹配入参）
     */
    @PostMapping("/profile/update")
    @CheckDataOwner(expr = "#dto.userId")
    public Result<String> updateProfile(@Valid @RequestBody UserProfileDTO dto) {
        return Result.success("个人资料更新成功: " + dto.getNickname());
    }

    /**
     * 基于 @CheckDataOwner 与 DataOwnerProvider 反查 SPI 的订单防水平越权取消
     */
    @PostMapping("/orders/{orderId}/cancel")
    @CheckDataOwner(expr = "#orderId", provider = SampleOrderDataOwnerProvider.class)
    public Result<String> cancelOrder(@PathVariable("orderId") Long orderId) {
        return Result.success("订单已成功取消: " + orderId);
    }

    /**
     * 编程式断言当前操作人本人归属
     */
    @PostMapping("/assert-self")
    public Result<String> assertSelf(@RequestParam("userId") String userId) {
        LoginHelper.checkSelf(userId);
        return Result.success("本人所有权断言校验通过: " + userId);
    }

    /**
     * 编程式断言目标租户与当前操作人所在租户一致
     */
    @PostMapping("/assert-tenant")
    public Result<String> assertTenant(@RequestParam("tenantId") String tenantId) {
        LoginHelper.checkBelongsToTenant(tenantId);
        return Result.success("当前租户归属断言校验通过: " + tenantId);
    }
}
```

### 4.3 核心机制与生产边界说明

1. **AOP 全域方法鉴权与时序**：
   - 注册全局切面 `SaAroundAnnotationPointcutAdvisor`，不仅拦截 Controller 入口，更穿透拦截任何 Spring 受管 Service 业务组件上的 `@SaCheckRole`、`@SaCheckPermission`、`@SaCheckSafe` 等注解；
   - 支持 `mode = SaMode.OR`（任一匹配）与 `mode = SaMode.AND`（全部匹配），彻底消除网关与服务层重复校验开销；
2. **二级安全认证时间窗机制**：
   - 涉及敏感资金结算或提权操作时，调用 `LoginHelper.openSafe(timeout)` 为当前 Token 开启临时安全认证态（默认或自定义秒数）；
   - 在安全窗口期内，调用 `@SaCheckSafe` 或 `LoginHelper.checkSafe()` 均可直接通过，无需用户频繁输入密码；
   - 高危操作完成后可显式调用 `LoginHelper.closeSafe()` 提前关闭安全窗，超时后也会自动失效，彻底防御会话劫持后发起次生越权操作；
3. **基于 SpEL 与 SPI 的水平越权防御 (IDOR)**：
   - **直接匹配模型**：`@CheckDataOwner(expr = "#dto.userId")` 通过 Spring Expression Language 提取方法入参中的用户 ID，自动与当前登录人 `LoginHelper.getUserId()` 进行常数时间等值比对，非本人直接阻断；
   - **反查 SPI 模型**：若入参为订单 ID 等非用户主键，指定 `provider = SampleOrderDataOwnerProvider.class` 实现 `DataOwnerProvider<ID>` SPI，由底层反查该资源的真实所有者并进行比对；
   - **超级管理员特权豁免**：注解默认 `allowAdmin = true`，当操作人为超级管理员（`LoginHelper.isSuperAdmin()`）时自动放行，兼顾运营运维支撑；
4. **统一异常转译契约 (`SaTokenExceptionHandler`)**：
   - 未登录抛出的 `NotLoginException` 统一转译为 HTTP 200 + `Result.fail(401, "未提供访问凭据 (Token)")`；
   - 角色/权限不足抛出的 `NotRoleException` / `NotPermissionException` 统一转译为 HTTP 200 + `Result.fail(403, "无权访问：缺少所需角色/权限")`；
   - 二级认证未通过抛出的 `NotSafeException` 统一转译为 HTTP 200 + `Result.fail(403, "无权访问：当前操作需要二级安全认证")`；
   - 数据所有权校验未通过抛出的 `SaTokenDataOwnerException` 统一转译为 HTTP 200 + `Result.fail(403, "无权操作该资源：数据所有权归属校验未通过")`。

---

## 5. 两级权限缓存、防雪崩击穿与分布式集群同步

### 场景痛点
1. **频繁鉴权数据库打崩**：每个标注 `@SaCheckPermission` 的接口每次调用都会触发权限查询，若直接查库或高频查询 Redis，吞吐量将受到严重制约；
2. **并发回源瞬时击穿**：热点用户或全局角色缓存失效时，大量高并发请求同时穿透回源打满数据库连接池；
3. **集中过期雪崩**：批量登录或批量刷新的权限缓存在同一时间点集中失效，引发后端存储雪崩；
4. **集群异地本地缓存脏读**：管理员在节点 A 调整了员工权限，节点 B 的本地 Caffeine 缓存无法感知，导致员工在被降权后仍能在节点 B 越权调用接口。

### 5.1 专属 YAML 配置片段

```yaml
ateng:
  boot:
    satoken:
      permission-cache:
        enabled: true               # 是否开启两级权限缓存 (默认: true)
        timeout: 5m                 # 权限与角色缓存有效期，强类型 Duration (默认: 5m)
        empty-ttl: 60s              # 空权限/空角色防穿透哨兵缓存有效期，强类型 Duration (默认: 60s)
      cluster-sync:
        enabled: true               # 是否开启分布式集群即时同步与广播作废 (默认: true)
        channel: ateng:satoken:cluster-sync # Redis Pub/Sub 广播频道名称
```

### 5.2 面向实战的业务 Java 示例（源自 `PermissionCacheController.java`）

```java
@Validated
@RestController
@RequestMapping("/sample/satoken/permission-cache")
@RequiredArgsConstructor
public class PermissionCacheController {

    private final PermissionCacheService permissionCacheService;

    /**
     * 读取当前登录主体经由两级缓存切片解析的角色列表
     */
    @GetMapping("/roles")
    public Result<Set<String>> getCurrentRoles() {
        LoginHelper.checkLogin();
        return Result.success(LoginHelper.getRoles());
    }

    /**
     * 读取当前登录主体经由两级缓存切片解析的细粒度权限码集合
     */
    @GetMapping("/permissions")
    public Result<Set<String>> getCurrentPermissions() {
        LoginHelper.checkLogin();
        return Result.success(LoginHelper.getPermissions());
    }

    /**
     * 精准刷新指定角色关联的权限缓存（秒级淘汰两级缓存并广播异地节点）
     */
    @PostMapping("/refresh-role")
    public Result<String> refreshRole(@RequestParam("roleCode") String roleCode) {
        permissionCacheService.refreshRolePermissions(roleCode);
        return Result.success("角色权限缓存已刷新: " + roleCode);
    }

    /**
     * 精准刷新指定用户的角色与直属权限缓存
     */
    @PostMapping("/refresh-user")
    public Result<String> refreshUser(@RequestParam("userId") String userId) {
        permissionCacheService.refreshUserPermissions(userId);
        return Result.success("用户权限缓存已刷新: " + userId);
    }

    /**
     * 清空全量两级权限缓存
     */
    @PostMapping("/clear-all")
    public Result<Void> clearAll() {
        permissionCacheService.clearAll();
        return Result.success();
    }
}
```

### 5.3 核心机制与生产边界说明

1. **L1 Caffeine + L2 Redis 条带锁并发防击穿**：
   - `CachedStpInterface` 深度集成 `TwoLevelCacheManager`；
   - 本地进程内 L1 Caffeine 纳秒微缓存承接 99.9% 的瞬时高并发读；
   - 缓存未命中回源时，底层采用细粒度并发条带锁（64 槽位），限制同一用户/同一角色仅有单一并发线程执行穿透回源，其余线程自旋复用结果，彻底消除瞬时击穿；
2. **防穿透哨兵缓存与动态 Jitter 防雪崩**：
   - **空值防穿透**：当用户无任何角色或无任何权限时，写入 `__EMPTY__` 哨兵缓存（默认有效期 60s），阻断恶意请求频繁用空权限穿透数据库；
   - **动态随机抖动防雪崩**：向 Redis 写入两级缓存时，附加 10%~20% 的动态 Jitter 随机抖动时间，杜绝同一批用户的权限缓存在同一秒内集中失效引发数据库雪崩；
3. **多账号体系 `loginType` 细粒度穿透**：
   - 权限加载 SPI `UserRolePermissionLoader` 标配 `(userId, loginType)` 与 `(roleCode, loginType)` 重载；
   - `CachedStpInterface` 在解析角色与权限时，将当前账号体系的 `loginType` 完整透传至缓存键与数据加载器，保障管理后台与客户端会员的权限缓存物理隔离；
4. **集群 Pub/Sub 广播与本机自消费防卫**：
   - 管理员调整权限并调用 `PermissionCacheService` 刷新时，当前节点立即驱逐本地 L1 缓存与 Redis L2 缓存，并通过 Redis Pub/Sub 频道广播即时作废消息；
   - 广播消息携带发布节点的全局唯一 UUID `instanceId`；发布节点监听到自身发出的消息时**直接静默丢弃**（自消费防卫），异地集群节点监听到后立即驱逐本地 L1 缓存，实现集群毫秒级权限热同步；
5. **纯内存确定性平滑降级 (Zero-Mock)**：
   - 当检测到 Redis 基础设施未启用（`ateng.boot.redis.enabled: false`）时，`CachedStpInterface` 自动平滑降级为纯本地 Caffeine 进程内自洽缓存与单机驱逐，不发起任何远程网络连接，单机开发或离线单元测试 100% 稳定运行。

---

## 6. 多账号体系独立登录与物理会话隔离（Multi-Account Architecture）

### 场景痛点
现代化综合业务系统往往同时包含管理后台（`admin`）、C 端商城会员（`member`）、开放平台应用（`app`）等多种完全不同的账号体系。若共用同一个 `StpUtil`，会导致 Token Header 冲突（如 `satoken` 串读）、登录凭据踩踏（C 端用户登录将同数字 ID 的后台管理员踢下线）等灾难性故障。原生 Sa-Token 虽支持自定义 `StpLogic`，但需要繁琐的手动配置和样板代码。

### 核心架构与隔离机制
`LoginHelper` 深度支持**多账号体系动态懒加载与物理隔离治理**：
- **动态懒加载注册**：通过 `LoginHelper.getStpLogic(loginType)` 在首次使用时线程安全注册对应体系的独立 `StpLogic`；
- **独立 Token 命名规范**：非主体系自动采用 `satoken-{loginType}` 作为独立 Token 请求头（例如 `satoken-admin`、`satoken-member`），主体系遵循默认 `satoken`；
- **全生命周期方法镜像**：所有会话操作（`login`, `logout`, `getLoginUser`, `getUserId`, `isLogin`, `checkLogin`, `getTokenValue`, `kickout`）均提供重载直接支持传入 `loginType`，保障多账号间会话完全物理隔离。

### 核心代码示例（源自 `SampleMultiAccountController.java` 与 `SampleSaTokenIntegrationTest.java`）

```java
@RestController
@RequestMapping("/api/multi-account")
public class SampleMultiAccountController {

    /**
     * 后台管理员登录（独立账号体系 admin，下发独立 Token 头 satoken-admin）
     */
    @PostMapping("/admin/login")
    public Result<String> adminLogin(@RequestBody LoginUser loginUser) {
        LoginHelper.login("admin", loginUser);
        return Result.success(LoginHelper.getTokenValue("admin"));
    }

    /**
     * C端商城会员登录（独立账号体系 member，下发独立 Token 头 satoken-member）
     */
    @PostMapping("/member/login")
    public Result<String> memberLogin(@RequestBody LoginUser loginUser) {
        LoginHelper.login("member", loginUser);
        return Result.success(LoginHelper.getTokenValue("member"));
    }

    /**
     * 获取管理员端信息（仅校验 admin 账号体系登录态）
     */
    @GetMapping("/admin/me")
    public Result<LoginUser> getAdminUser() {
        LoginHelper.checkLogin("admin");
        return Result.success(LoginHelper.getLoginUser("admin"));
    }

    /**
     * 获取会员端信息（仅校验 member 账号体系登录态）
     */
    @GetMapping("/member/me")
    public Result<LoginUser> getMemberUser() {
        LoginHelper.checkLogin("member");
        return Result.success(LoginHelper.getLoginUser("member"));
    }
}
```

### 预期多账号请求隔离呈现
- 管理员请求携带：
  ```http
  satoken-admin: b38e4a11-8c43-4ef0-951a-d4a9ec03df6a
  ```
- 会员请求携带：
  ```http
  satoken-member: c76f9202-2d1b-4b2a-a9e3-e18fa4d9bb10
  ```
两者互相独立，管理员下线或注销不会影响同 ID 的普通会员。

---

## 7. 客户端 IP 自动嗅探与双轨防爆破熔断（Dual-Track Brute Force Defense）

### 场景痛点
在开放互联网接入环境下，黑客常用的攻击手段不仅包括针对单个账号的字典爆破，还包括从同一攻击 IP 针对海量不同账号发起的**分布式撞库攻击**。若仅防护账号维度，恶意攻击 IP 可以轮流尝试不同用户名，导致系统整体鉴权接口被刷爆。此外，企业办公内网、跳板机或网关出口 IP 若被一并封禁，将导致大批合法员工被集体误杀。

### 架构方案
`LoginFailureLockService` 采用**双轨自适应防暴力破解与 IP 嗅探熔断体系**：
1. **自动透明 IP 嗅探**：支持从 `X-Forwarded-For`、`X-Real-IP`、反向代理候选头及 Spring Web `RequestContextHolder` / Jakarta Servlet 中弹性嗅探客户端真实 IP；
2. **账号 + IP 双轨滑动计数**：
   - **账号轨**：统计同一账号连续密码错误次数，达到上限触发账号封禁；
   - **IP 轨**：统计同一客户端 IP 连续密码错误次数，达到上限触发 IP 临时熔断；
3. **Fail-Fast 快速失败**：第 6 次来自被熔断 IP 的任何登录请求（即便尝试全新账号），直接在认证最前线拦截并返回 403，保护底层数据库与密码 Hash 计算不被穿透；
4. **CIDR 与 IP 白名单豁免**：配置 `ip-white-list`（支持纯 IP 如 `127.0.0.1`、`::1` 及 CIDR 网段如 `192.168.1.0/24`、`10.0.0.0/8`），白名单 IP 永远豁免 IP 维度惩罚熔断。

### 核心代码示例（源自 `SaTokenSampleController.java` 与 `SaTokenSampleService.java`）

```java
@Service
public class SaTokenSampleService {

    @Autowired
    private ObjectProvider<LoginFailureLockService> loginDefenseServiceProvider;

    public String loginWithDefense(LoginDefenseRequest request) {
        String username = request.getUsername();
        String password = request.getPassword();

        LoginFailureLockService lockService = loginDefenseServiceProvider.getIfAvailable();
        if (lockService != null) {
            // 前置快速失败防卫：若账号已被锁定或客户端 IP 已被熔断直接 Fail-Fast 403 阻断
            lockService.checkLocked(username);
        }

        // 校验密码
        boolean passwordCorrect = checkPassword(username, password);
        if (!passwordCorrect) {
            if (lockService != null) {
                // 原子累计账号与客户端 IP 失败次数（自动嗅探当前 HTTP 客户端 IP）
                int remain = lockService.recordFailure(username);
                if (remain == 0) {
                    throw new BusinessException(CommonResultCode.FORBIDDEN, "登录失败次数过多，已被锁定，请稍后再试");
                }
                throw new BusinessException(CommonResultCode.UNAUTHORIZED, "账号或密码错误，剩余重试机会: " + remain + " 次");
            }
            throw new BusinessException(CommonResultCode.UNAUTHORIZED, "账号或密码错误");
        }

        // 登录成功，对称清空失败计数器
        if (lockService != null) {
            lockService.clearFailure(username);
        }

        // 签发凭据...
        return LoginHelper.getTokenValue();
    }
}
```

### 预期 IP 熔断拦截日志与响应
- 攻击 IP 连续尝试 5 次不同账号均失败后，控制台触发告警：
```text
2026-10-28 16:16:14.350 WARN  i.g.a.b.s.d.LoginFailureLockService : 【防暴力破解/内存模式】客户端 IP 连续登录失败次数达到上限，已触发 IP 临时熔断: ip=198.51.100.99, count=5, lockDuration=PT15M
```
- 第 6 次请求直接被快速失败前置阻断（HTTP 200 + 403）：
```json
{
  "code": 403,
  "msg": "客户端 IP 连续登录失败次数超限，已被临时锁定，剩余锁定时间: 899 秒",
  "data": null,
  "success": false
}
```

---

## 8. 在线会话实时监控与精准强退治理（Online User Governance）

### 场景痛点
生产系统运营与运维审计要求实时查看“当前有哪些用户在线”、“各用户是从 PC 还是 APP 登录的”、“累计在线会话总数是多少”，并在发现异常会话、员工离职或账号失窃时，能够**精准按 Token 凭据或指定设备类型踢人下线**。如果采用传统 `keys *` 或全量拉取，会导致 Redis 单线程阻塞或应用内存 OOM。

### 核心架构与增量游标检索
`LoginHelper` 提供了安全受管的在线用户治理能力：
- **安全增量游标检索**：底层统一基于 Redis `SCAN` 增量游标（纯内存模式基于线程安全快照迭代），支持模糊关键字匹配（匹配账号、用户名、用户 ID、Token），严格分页切片，杜绝大 key 阻塞与内存膨胀；
- **多账号与多设备识别**：自动聚合会话关联的 `LoginUser`，提取 `loginDevice`、`loginTime`、`tokenTimeout` 等多维监控字段；
- **精准下线治理**：支持按指定 Token 强退（`kickoutByToken`）与按指定设备类型强退（`kickoutByDevice`）。

### 核心代码示例（源自 `SampleOnlineUserController.java` 与 `SampleSaTokenIntegrationTest.java`）

```java
@RestController
@RequestMapping("/api/auth/online")
public class SampleOnlineUserController {

    /**
     * 获取在线用户会话总数
     */
    @GetMapping("/count")
    public Result<Long> getOnlineCount(@RequestParam(value = "loginType", required = false) String loginType) {
        if (loginType != null && !loginType.isBlank()) {
            return Result.success(LoginHelper.getOnlineUserCount(loginType));
        }
        return Result.success(LoginHelper.getOnlineUserCount());
    }

    /**
     * 分页查询在线用户 PageResult
     */
    @GetMapping("/page")
    public Result<PageResult<OnlineUser>> searchOnlineUserPage(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return Result.success(LoginHelper.searchOnlineUserPage(keyword, page, size));
    }

    /**
     * 根据 Token 强制指定会话下线
     */
    @PostMapping("/kickout-token")
    public Result<Void> kickoutByToken(@RequestParam("tokenValue") String tokenValue) {
        LoginHelper.kickoutByToken(tokenValue);
        return Result.success();
    }

    /**
     * 根据设备类型精准强退用户指定端会话（例如仅强退 APP 端，保留 PC 端活跃）
     */
    @PostMapping("/kickout-device")
    public Result<Void> kickoutDevice(@RequestParam("userId") String userId,
                                      @RequestParam("device") String device) {
        LoginHelper.kickoutDevice(userId, device);
        return Result.success();
    }
}
```

---

## 9. 数据库驱动的动态 URL 路径权限匹配管道（Dynamic Path Permission SPI）

### 场景痛点
大型企业系统通常要求将“API 路径与角色/权限的对应关系”交由管理员在前端可视化配置并持久化保存在数据库中。如果仅依赖代码中的 `@SaCheckPermission` 注解，一旦权限发生重构或新增路径规则，必须重新编译打包并发布代码；而自建拦截器又容易与框架的白名单与上下文注入发生时序错乱。

### 架构方案
`ateng-boot-satoken` 在 `AtengSaTokenInterceptor` 中无缝内嵌**动态路径权限匹配管道**：
1. **SPI 优雅扩展**：业务只需声明实现 `DynamicPathPermissionProvider` SPI 接口，即可从数据库、字典表或配置中心加载 `List<PathPermissionRule>`；
2. **AntPath + HTTP Method 双重匹配**：支持精确路径（如 `/api/orders`）与通配路径（如 `/api/dynamic/resource/**`），并精确比对 HTTP 请求方法（GET、POST、PUT、DELETE 或 `*` 通配）；
3. **丰富鉴权模式**：支持 `SaMode.AND`（必须同时具备多个权限）与 `SaMode.OR`（具备任一权限即放行）；
4. **零侵入与零注解**：Controller 方法无需编写任何 `@SaCheckPermission` 注解，只要命中规则管道，拦截器自动调用底层 `StpUtil.checkPermissionAnd/Or(...)` 实施强力防御。

### 核心代码示例（源自 `SampleDynamicPathPermissionProvider.java` 与 `SampleDynamicPermissionController.java`）

#### 1. 实现 SPI 动态路径规则提供者
```java
@Component
public class SampleDynamicPathPermissionProvider implements DynamicPathPermissionProvider {

    @Override
    public List<PathPermissionRule> getPathPermissionRules() {
        return List.of(
                PathPermissionRule.of("/api/dynamic/resource/data", "GET", "dynamic:resource:view"),
                PathPermissionRule.of("/api/dynamic/resource/data", "POST", "dynamic:resource:create"),
                PathPermissionRule.of("/api/dynamic/resource/data", "DELETE", SaMode.AND, "dynamic:resource:delete", "dynamic:resource:audit")
        );
    }
}
```

#### 2. Controller 接口无需标注注解
```java
@RestController
@RequestMapping("/api/dynamic/resource")
public class SampleDynamicPermissionController {

    @GetMapping("/data")
    public Result<String> getData() {
        // 需 dynamic:resource:view 权限，拦截器管道动态鉴权
        return Result.success("动态资源查询成功");
    }

    @PostMapping("/data")
    public Result<String> createData() {
        // 需 dynamic:resource:create 权限
        return Result.success("动态资源创建成功");
    }

    @DeleteMapping("/data")
    public Result<String> deleteData() {
        // 需 dynamic:resource:delete AND dynamic:resource:audit 权限
        return Result.success("动态资源删除成功");
    }

    @GetMapping("/unprotected")
    public Result<String> unprotected() {
        // 未受动态规则约束，正常放行通行
        return Result.success("未受规则约束的公开资源");
    }
}
```

---

## 10. 微服务内部零信任调用与客户端拦截器自动装配（Same-Token）

### 场景痛点
在微服务架构中，内部服务之间往往有专用的 HTTP / RPC 接口。若缺乏调用凭据验证，攻击者或外网恶意请求一旦绕过 API 网关，就可直接向内部微服务发送伪造请求，造成重大越权安全事故。此外，调用方发起内部请求时，若需要在每处业务代码中手工拼接请求头，代码极其臃肿且极易遗漏。

### 核心代码示例（源自 `SampleSaTokenIntegrationTest.java`）

#### 1. 服务端接口声明零信任保护
只需在 Controller 类或方法上标注 `@CheckSameToken`：

```java
@RestController
@RequestMapping("/api/auth/internal")
public class InternalSyncController {

    @GetMapping("/data")
    @CheckSameToken // 拦截并校验微服务内部调用凭证
    public Result<String> getInternalData() {
        return Result.success("内部机密数据访问成功");
    }
}
```

#### 2. 发起端 RestClient / RestTemplate 拦截器全自动装配
当配置 `ateng.boot.satoken.same-token.auto-inject-client: true`（默认启用）时，框架自动注册 `sameTokenRestClientCustomizer` 与 `sameTokenRestTemplateCustomizer`。由 Spring 受管的 `RestClient.Builder` 与 `RestTemplate` 会**全自动挂载 `SameTokenClientHttpRequestInterceptor`**，在发出内部请求时自动注入 `SA-SAME-TOKEN` 请求头：

```java
@Service
public class RemoteCallService {

    @Autowired
    private RestTemplateBuilder restTemplateBuilder;

    public void callInternalService() {
        RestTemplate restTemplate = restTemplateBuilder.build();
        // 底层拦截器已自动附带 SA-SAME-TOKEN 请求头，无需业务手工干预
        String res = restTemplate.getForObject("http://internal-service/api/auth/internal/data", String.class);
    }
}
```

### 预期拦截呈现
若未经网关伪造请求或未携带合法的 Same-Token，AOP 切面立即阻断并返回：
```json
{
  "code": 403,
  "msg": "微服务内部鉴权凭据无效或已失效",
  "data": null,
  "success": false
}
```

---

## 11. Spring 原生安全事件总线

### 场景痛点
安全合规与风控要求记录登录日志、异常登出、踢人下线、账号封禁等关键事件。如果把审计日志代码写在登录方法中，会造成强耦合；原生 Sa-Token 的监听器接口非 Spring 标准生态，不便于与其他组件解耦。

### 核心代码示例
`ateng-boot-satoken` 内置 `SaTokenEventPublisher`，自动将 Sa-Token 生命周期事件桥接并广播为 Spring 原生 `ApplicationEvent`。业务模块只需声明普通 `@EventListener` 即可非侵入式接收：

```java
@Component
public class SecurityAuditListener {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditListener.class);

    @EventListener
    public void onLogin(SaLoginEvent event) {
        log.info("[安全审计] 用户登录成功! loginType={}, loginId={}, token={}", 
                event.getLoginType(), event.getLoginId(), event.getTokenValue());
    }

    @EventListener
    public void onLogout(SaLogoutEvent event) {
        log.info("[安全审计] 用户安全注销! loginId={}, token={}", event.getLoginId(), event.getTokenValue());
    }

    @EventListener
    public void onKickout(SaKickoutEvent event) {
        log.warn("[安全审计] 用户被管理员踢下线! loginId={}, token={}", event.getLoginId(), event.getTokenValue());
    }

    @EventListener
    public void onDisable(SaDisableEvent event) {
        log.warn("[安全审计] 用户被安全封禁! loginId={}, 服务={}, 封禁时长={}秒", 
                event.getLoginId(), event.getService(), event.getDisableTime());
    }
}
```

---

## 12. 水平越权防御与数据所有权校验体系 (@CheckDataOwner & LoginHelper.checkSelf)

### 场景痛点
传统 RBAC 权限体系通常仅校验接口“操作权限”（例如判断当前用户是否具备 `user:update` 权限），但无法防范恶意用户通过篡改请求路径参数或请求体中的 `userId`、`orderId` 来修改他人数据的**水平越权漏洞（BOLA / IDOR）**。若在每个业务 Service 中重复编写 `if (!userId.equals(currentUserId))`，极易出现漏检。

### 核心代码示例

#### 1. 声明式 SpEL 数据所有权校验
通过在 Controller 方法上标注 `@CheckDataOwner`，支持直接通过 SpEL 表达式提取当前请求入参的目标归属人：

```java
@RestController
@RequestMapping("/api/auth")
public class UserProfileController {

    /**
     * 修改个人资料接口（直接通过 SpEL 校验 userId 数据所有权）
     */
    @PostMapping("/user/profile")
    @CheckDataOwner(expr = "#dto.userId")
    public Result<String> updateProfile(@RequestBody UserProfileDTO dto) {
        return Result.success("个人资料更新成功: " + dto.getUserId());
    }

    /**
     * 订单取消接口（间接通过 SPI 反查 orderId 数据所有权）
     */
    @PostMapping("/order/{orderId}/cancel")
    @CheckDataOwner(expr = "#orderId", provider = SampleOrderDataOwnerProvider.class, message = "无权取消该订单")
    public Result<String> cancelOrder(@PathVariable("orderId") Long orderId) {
        return Result.success("订单取消成功: " + orderId);
    }
}
```

#### 2. 编程式极简防卫断言
在后台逻辑或批量操作中，可直接调用 `LoginHelper.checkSelf` 或 `LoginHelper.checkBelongsToTenant` 实施前置卫语句快速失败：

```java
@Service
public class OrderService {

    public void processUserOrder(String targetUserId, String targetTenantId) {
        // 1. 断言当前操作人是否为本人（超级管理员自动豁免放行）
        LoginHelper.checkSelf(targetUserId, "无权访问他人订单数据");

        // 2. 断言当前操作人是否归属于目标租户
        LoginHelper.checkBelongsToTenant(targetTenantId, "非法跨租户数据访问");
    }
}
```

---

## 13. 声明式公开访问与动态白名单 (@Anonymous & SaTokenWhitelistProvider)

### 场景痛点
在微服务或单体架构中，很多接口（如登录、验证码、对外静态公文、健康检查）无需鉴权放行。传统做法是在 `application.yml` 的 `white-list` 中硬编码长路径，这不仅繁琐，而且一旦接口重命名或迁移极易引发 401 漏检事故；此外，业务插件或第三方 Starter 无法在运行时动态扩充白名单。

### 核心代码示例

#### 1. 声明式 `@Anonymous` 注解放行
支持在 Controller 方法或类级别直接标注 `@Anonymous`，启动时 `AnonymousUrlProcessor` 会自动扫描并将 Spring MVC 路径变量占位符（如 `{id}`）自适应转译为 AntPath 通配符（如 `*`）：

```java
@RestController
@RequestMapping("/api/auth/public")
public class PublicArticleController {

    // 方法级匿名放行
    @GetMapping("/article/{id}")
    @Anonymous
    public Result<String> getPublicArticle(@PathVariable("id") Long id) {
        return Result.success("公开文章详情: " + id);
    }
}
```

#### 2. SPI 动态白名单扩展 (`SaTokenWhitelistProvider`)
业务模块或插件 Starter 可声明实现 `SaTokenWhitelistProvider` SPI 接口，在运行时向鉴权拦截器动态注入自定义白名单路径：

```java
@Component
public class CustomPluginWhitelistProvider implements SaTokenWhitelistProvider {

    @Override
    public Set<String> getWhitelist() {
        return Set.of("/api/custom-spi/**", "/public/third-party/**");
    }
}
```

---

## 14. 身份模拟与双维运维审计体系 (RunAs / Impersonate)

### 场景痛点
在大型企业系统中，客服代客下单、技术运维排障、故障复现等场景下，管理员需要临时以特定用户的身份调用业务接口。如果直接篡改当前会话，会导致真实操作人丢失引发重大审计盲区；若手工在 Service 层层透传 `impersonateUserId`，则持久层审计（`create_by` / `update_by`）与数据权限断言无法透明生效。

### 核心代码示例

#### 1. 跨请求会话模拟 (`switchTo` / `endSwitch`)
适用于客服会话模拟与运维后台代客操作，模拟态信息跨 HTTP 请求持久维护于会话 Session 中：

```java
@RestController
@RequestMapping("/api/auth/impersonate")
public class ImpersonateController {

    /**
     * 运维管理员切换为目标用户身份
     */
    @PostMapping("/switch")
    public Result<String> impersonateSwitch(@NotBlank(message = "目标用户标识不能为空") @RequestParam("targetUserId") String targetUserId) {
        LoginHelper.checkLogin();
        // 切换身份（自动记录原始登录管理员为 originalUserId，即便多次连续切换也不丢失最初源头）
        LoginHelper.switchTo(targetUserId);
        return Result.success("成功切换身份至: " + targetUserId);
    }

    /**
     * 结束身份模拟，恢复真实登录人
     */
    @PostMapping("/end")
    public Result<String> impersonateEnd() {
        LoginHelper.checkLogin();
        // 结束模拟并彻底清理会话模拟元数据
        LoginHelper.endSwitch();
        return Result.success("身份模拟已结束，已恢复原始身份");
    }
}
```

#### 2. 瞬态代码块安全模拟 (`runAs`)
适用于在特定业务方法或异步任务内部瞬态以目标用户身份运行，基于 ThreadLocal 栈式暂存现场与 `finally` 可靠清理：

```java
@Service
public class OrderCompensationService {

    public void compensateUserOrder(String targetUserId) {
        // 瞬态切换为 targetUserId 执行，异常或正常退出均确定性恢复现场
        LoginHelper.runAs(targetUserId, () -> {
            log.info("当前临时生效用户: {}, 发起人: {}", LoginHelper.getUserId(), LoginHelper.getOriginalUserId());
            // 此时调用业务方法，MyBatis-Plus 审计自动填入 targetUserId
            doCreateOrder();
        });
    }
}
```

---

## 15. OpenAPI / Knife4j 鉴权弹窗无侵入自动化

### 场景痛点
在集成 Knife4j 或 Swagger UI 时，默认情况下接口文档页面无法便捷输入 Token，开发者测试接口需要反复在 Headers 中手动填写 `satoken` 请求头，体验极其繁琐。

### 自动化效果
当工程中引入 `springdoc-openapi-starter-webmvc-ui` 时，`ateng-boot-satoken` 自动装配 `SaTokenOpenApiCustomizer`：
1. 自动在 OpenAPI Components 中注册 `SecurityScheme`（名称：`satoken`，类型：`APIKEY`，位置：`HEADER`）；
2. 自动注入全局 `SecurityRequirement`；
3. 打开 Knife4j UI 页面右上角直接展示「Authorize / 授权」锁图标，填入 Token 后全局接口全自动携带。

---

## 16. 分布式集群即时同步与广播作废 (Cluster Sync via Redis Pub/Sub)

### 场景痛点
在微服务多副本集群中，各节点会在本地内存或 L1 缓存维护角色与权限数据。当 Node A 修改了权限或踢人下线时，若仅更新 Redis 中心，Node B、Node C 等节点依然持有陈旧本地缓存，造成严重权限脏读与越权风险。

### 核心架构与自消费丢弃
`ateng-boot-satoken` 依托轻量 Redis Pub/Sub 广播构建集群即时同步体系：
- **节点唯一 ID**：每个节点启动生成 `instanceId`（无锁 `fastSimpleUUID`）；
- **自消费防卫**：本机接收到自己广播的消息时直接静默丢弃（Self-Consumption Guard），杜绝重入与广播风暴；
- **精准驱逐**：异地节点接收到广播后，精准驱逐本地两级缓存中对应的用户/角色条目。

---

## 17. 配置项全景表（application.yml）

```yaml
# Sa-Token 官方原生核心配置
sa-token:
  token-name: satoken             # Token 名称 (同时也是 Cookie 与 Header 名称)
  timeout: 2592000                # Token 有效期，单位秒 (默认30天，-1表示永久)
  active-timeout: -1              # Token 活跃有效期 (即指定时间内无操作就视为冻结，-1表示不限制)
  is-concurrent: true             # 是否允许同一账号并发登录 (为 true 时允许一起登录, 为 false 时新登录挤掉旧登录)
  is-share: false                 # 在多人登录同一账号时，是否共用一个 Token (为 false 时每次登录新建一个 Token)
  token-style: uuid               # Token 风格 (uuid、simple-uuid、random-32、random-64、tik 等)
  is-log: false                   # 是否在控制台打印 Sa-Token 内部操作日志
  is-read-header: true            # 是否从请求头读取 Token
  is-read-cookie: false           # 是否从 Cookie 读取 Token (前后端分离架构建议关闭)
  same-token-timeout: 86400       # 微服务内部 Same-Token 密钥有效时间 (秒)

# ateng-boot 专属安全增强配置
ateng:
  boot:
    satoken:
      enabled: true               # 是否开启 ateng-boot-satoken 模块增强功能 (默认: true)
      primary-login-type: login   # 系统默认主账号体系类型标识 (默认: login)
      white-list:                 # 全局路由白名单路径列表（内置基础端点，底层自动与 interceptor.excludes 合并去重）
        - "/doc.html"
        - "/webjars/**"
        - "/swagger-resources/**"
        - "/v3/api-docs/**"
        - "/favicon.ico"
        - "/actuator/**"
      token:                      # 强类型 Token 核心属性桥接配置
        timeout: 24h              # Token 绝对有效期，强类型 Duration (默认: 24h)
        active-timeout: 30m       # Token 活跃空闲超时，强类型 Duration (默认: 30m)
        is-concurrent: true       # 同一账号是否允许并发登录 (默认: true)
        is-share: true            # 在多人登录同一账号时，是否共用同一个 Token (默认: true)
        max-login-count: 12       # 同一账号最大并发登录终端数 (默认: 12)
        token-style: uuid         # Token 算法生成风格 (默认: uuid)
      interceptor:                # 路由拦截器与白名单放行配置
        enabled: true             # 是否挂载 Sa-Token 路由拦截器 (默认: true)
        check-login: false        # 是否开启全局路由强制登录校验 (默认: false)
        includes:                 # 拦截匹配的 URL 路径列表 (默认: /**)
          - "/**"
        excludes:                 # 业务定制排除放行的白名单 URL 路径列表（底层自动与 white-list 聚合去重）
          - "/api/auth/login"
          - "/doc.html"
          - "/webjars/**"
          - "/v3/api-docs/**"
          - "/favicon.ico"
      same-token:                 # 微服务内部 Same-Token 零信任鉴权配置
        enabled: true             # 是否启用内部 Same-Token 校验 (默认: true)
        header-name: SA-SAME-TOKEN # Same-Token 传输请求头名称 (默认: SA-SAME-TOKEN)
        token-value: ""           # 约定的内部调用固定密钥凭据 (留空时采用 Sa-Token 动态时间戳凭据)
        auto-inject-client: true  # 是否自动为 RestClient/RestTemplate 注入客户端出站拦截器 (默认: true)
      permission-cache:           # 权限与角色多级缓存治理配置
        enabled: true             # 是否开启权限码与角色缓存 (默认: true)
        timeout: 5m               # 权限与角色缓存有效期，强类型 Duration (默认: 5m)
        empty-ttl: 60s            # 空权限/空角色防穿透哨兵缓存有效期，强类型 Duration (默认: 60s)
      cluster-sync:               # 分布式集群即时同步与广播作废配置
        enabled: true             # 是否开启分布式集群即时同步与广播作废 (默认: true)
        channel: ateng:satoken:cluster-sync # Redis Pub/Sub 广播频道名称 (默认: ateng:satoken:cluster-sync)
      login-defense:              # 登录防暴力破解策略器配置
        enabled: true             # 是否开启登录防暴力破解防护 (默认: false)
        max-retry-count: 5        # 允许账号最大连续登录失败重试次数 (默认: 5)
        lock-time: 15m            # 达到失败上限后的账号封禁锁定时间，强类型 Duration (默认: 15m)
        window-time: 15m          # 账号失败计数滑动统计时间窗口，强类型 Duration (默认: 15m)
        ip-max-retry-count: 30    # 客户端 IP 最大连续登录失败重试次数 (默认: 30)
        ip-lock-time: 30m         # 客户端 IP 达到失败上限后的临时熔断锁定时间，强类型 Duration (默认: 30m)
        ip-window-time: 15m       # 客户端 IP 失败计数滑动统计时间窗口，强类型 Duration (默认: 15m)
        ip-white-list:            # 客户端 IP 防爆破白名单列表（支持单个 IPv4/IPv6 与 CIDR 网段如 10.0.0.0/8）
          - "127.0.0.1"
          - "0:0:0:0:0:0:0:1"
          - "::1"
      data-owner:                 # 数据所有权 (@CheckDataOwner) 越权防御切面配置
        enabled: true             # 是否启用数据所有权切面防御 (默认: true)
      openapi:                    # OpenAPI / Knife4j 鉴权弹窗配置
        enabled: true             # 是否自动为 Knife4j / Swagger 注入鉴权弹窗 (默认: true)
        token-name: satoken       # 鉴权组件名称与请求头 Key (默认: satoken)
```

---

## 18. 生产扩展与接管实践 (Customization & Extensibility)

### 1. 自定义权限与角色业务数据源 (`UserRolePermissionLoader`)
框架默认提供 `DefaultUserRolePermissionLoader` 从当前会话直接读取角色与权限集合。当需要在鉴权时从数据库持久层动态拉取最新权限并接入两级缓存时，业务只需声明自定义 Bean 即可平滑接管：

```java
@Component
public class DatabaseUserRolePermissionLoader implements UserRolePermissionLoader {

    @Autowired
    private UserRoleService userRoleService;

    @Autowired
    private RolePermissionService rolePermissionService;

    @Override
    public List<String> loadRolesByUserId(String userId) {
        return userRoleService.getRoleCodesByUserId(userId);
    }

    @Override
    public List<String> loadPermissionsByRole(String roleCode) {
        return rolePermissionService.getPermissionCodesByRole(roleCode);
    }

    @Override
    public List<String> loadDirectPermissionsByUserId(String userId) {
        return Collections.emptyList();
    }
}
```

### 2. 注册数据库驱动的动态路径权限规则提供者 (`DynamicPathPermissionProvider`)
业务只需向 Spring 容器注入 `DynamicPathPermissionProvider` 实现，即可平滑扩展 URL 鉴权管道：

```java
@Component
public class DatabaseDynamicPathPermissionProvider implements DynamicPathPermissionProvider {

    @Autowired
    private SysMenuService menuService;

    @Override
    public List<PathPermissionRule> getPathPermissionRules() {
        // 从数据库查询所有挂载了权限码的 API 接口列表
        return menuService.listApiPermissionRules();
    }
}
```

### 3. 覆盖默认会话存储实现 (`SaTokenDao`)
`ateng-boot-satoken` 在检测到 Redis 依赖时自动装配 `SaTokenRedisDao`，未检测到时自动降级为单机内存字典。若企业需要对接专属分布式缓存，只需声明同类型 `@Bean`：

```java
@Configuration
public class CustomSecurityStorageConfiguration {

    @Bean
    @Primary
    public SaTokenDao customSaTokenDao(StringRedisTemplate stringRedisTemplate) {
        return new SaTokenRedisDao(stringRedisTemplate);
    }
}
```

---

## 19. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 1. 严格无状态会话与零状态泄漏防线
- **单例无状态**：所有安全组件（`LoginHelper`、`PermissionCacheService`、`AtengSaTokenInterceptor`、`SaTokenRedisDao`、`LoginFailureLockService`）均保持严格无状态，无任何成员变量共享可变业务数据；
- **全生命周期对称清理**：`AtengSaTokenInterceptor` 在 `afterCompletion` 的 `finally` 块中严格执行 `UserContextHolder.clear()` 与 `TenantContextHolder.clear()`，杜绝 Web 容器工作线程池复用引发的上下文串染与数据越权。

### 2. 多账号体系上下文独立与请求头隔离
- **物理隔离**：主体系使用默认 `satoken` 请求头，独立体系自动采用 `satoken-{loginType}` 请求头，两者生成的 Token 格式与 Session 存储物理隔离；
- **懒加载与无竞争**：`LoginHelper.getStpLogic` 采用 `computeIfAbsent` 线程安全初始化，未配置的体系零常驻开销。

### 3. 两级权限缓存条带锁并发防击穿与防雪崩
- **条带锁防击穿**：高并发同一用户或角色缓存失效时，仅持有对应条带锁的单线程执行穿透回源，其余并发线程等待读取最新缓存，消除击穿；
- **防穿透哨兵**：空值写入 `__EMPTY__` 哨兵，辅以短 TTL，阻断无效权限反复穿透；
- **防雪崩抖动**：写入分布式缓存时附加 0%~10% 的动态 Jitter 随机抖动，杜绝缓存同一时刻集中过期引发雪崩。

### 4. 双轨防爆破熔断与 CIDR 白名单豁免
- **双轨计数与滑动窗口**：独立累计账号密码错误与客户端真实 IP 错误，滑动窗口到期自动失效；
- **白名单精准豁免**：命中 `ip-white-list`（含 CIDR 子网掩码计算）的出口 IP 永不触发 IP 维度熔断，保障企业办公网与网关平稳运行；
- **锁定状态语义自洽**：被锁定时查询失败次数返回最大重试阈值，锁定期满后自动清零重置。

### 5. 在线用户游标增量检索与防 OOM
- **游标增量扫描**：在海量在线会话场景下，`LoginHelper.getOnlineUsers` 采用 Redis SCAN 游标分批拉取，严禁使用全量 keys 命令；
- **内存分页截断**：拉取到满足分页条数后即刻截断返回，保障高并发运维监控端点内存绝对可控。

### 6. 动态路径权限未匹配端点弹性放行
- **管道未命中透传**：请求未命中任何 `PathPermissionRule` 时，直接放行交由后续拦截器或 Controller 本身鉴权逻辑处理，绝不误杀公开接口；
- **HTTP Method 严格匹配**：精确校验 HTTP 请求方式（GET/POST/DELETE/PUT），杜绝相同 URL 不同操作方式的权限越权。

---

## 20. 架构决策与设计参考

- [ADR-0024: 企业级 Sa-Token 安全鉴权与分布式会话治理架构](../docs/adr/0024-enterprise-satoken-authentication-and-security-architecture.md)
- [ADR-0002: 显式统一响应契约](../docs/adr/0002-explicit-result-contract-no-response-body-advice.md)
- [ADR-0012: 生命周期分离的数据安全模型与内存真明文原则](../docs/adr/0012-lifecycle-separated-data-security.md)
- [ADR-0022: 双核驱动 Redis 基础设施与分布式协同架构](../docs/adr/0022-dual-core-redis-and-distributed-coordination-architecture.md)
