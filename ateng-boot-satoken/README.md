# ateng-boot-satoken 安全鉴权与分布式会话治理套件

> 基于 Sa-Token `1.46.0` 构建的 Spring Boot 3 工业级安全基础设施与分布式会话治理 Starter，提供强类型 `LoginUser` / `LoginHelper`、短效业务令牌 `TempTokenHelper`、双轨拦截与四维上下文全链路穿透、多账号（`loginType`）物理与缓存隔离、多账号感知的 `@CheckDataOwner` 水平越权防御、客户端 IP 自动嗅探双轨防爆破、微服务 Same-Token 零信任全闭环、`ReentrantLock` 虚拟线程安全及集群 Redis Pub/Sub 即时广播同步。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.satoken.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (`*Test` / `Sample`) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **会话登录** | 强类型登录与端隔离 | `LoginHelper` | `enabled`<br>`token.timeout`<br>`token.active-timeout`<br>`token.is-concurrent`<br>`token.is-share`<br>`token.max-login-count`<br>`token.token-style` | `login(LoginUser)` / `login(loginType, LoginUser)` / `login(LoginUser, deviceType)` / `login(loginType, LoginUser, deviceType)` / `login(LoginUser, SaLoginParameter)` / `login(loginType, LoginUser, SaLoginParameter)`（自动嗅探固化 `loginIp`） | `LoginHelperTest` / `AuthSessionController` |
| **会话注销** | 会话与设备注销 | `LoginHelper` | - | `logout([loginType])` / `logoutByTokenValue([loginType,] tokenValue)` / `logoutByDevice([loginType,] deviceType)` / `logoutByLoginId(loginId[, deviceType])` / `logoutByLoginType(loginType, loginId[, deviceType])` | `LoginHelperTest` / `AuthSessionController` |
| **状态与续签** | 状态校验与有效期续期 | `LoginHelper` | `token.timeout`<br>`token.active-timeout` | `isLogin([loginType])` / `checkLogin([loginType])` / `getTokenValue([loginType])` / `getTokenValueByLoginId(loginId[, deviceType])` / `getTokenValueByLoginType(loginType, loginId[, deviceType])` / `getTokenTimeout([loginType])` / `getSessionTimeout([loginType])` / `getLoginDeviceType([loginType])` / `renewTimeout([loginType,] Duration)` / `renewTimeoutByToken([loginType,] tokenValue, Duration)` | `LoginHelperTest` / `AuthSessionController` |
| **短效凭据** | 强类型临时业务令牌 | `TempTokenHelper`<br>`LoginHelper` | - | `createTempToken(Object, Duration)` / `parseTempToken(String)` / `parseTempToken(String, Class<T>)` / `getTempTokenTimeout(String)` / `deleteTempToken(String)`（零 `@class` 纯 JSON） | `TempTokenHelperTest` / `AuthSessionController` |
| **设备治理** | 踢人下线与同端顶替 | `LoginHelper` | `token.is-concurrent`<br>`token.is-share`<br>`token.max-login-count` | `kickout(loginId[, deviceType])` / `kickoutByLoginType(loginType, loginId[, deviceType])` / `kickoutByToken([loginType,] tokenValue)` / `kickoutDevice([loginType,] loginId, deviceType)` / `kickoutByDevice([loginType,] loginId, deviceType)` / `replaced(loginId[, deviceType])` / `replacedByLoginType(loginType, loginId[, deviceType])` | `LoginHelperOnlineUserTest` / `OnlineSessionController` |
| **账号封禁** | 阶梯封禁与受限校验 | `LoginHelper`<br>`@SaCheckDisable` | - | `disable(loginId, [service,] Duration / long)` / `disableByLoginType(loginType, loginId, [service,] Duration / long)` / `untieDisable(loginId[, service])` / `untieDisableByLoginType(...)` / `isDisable(...)` / `isDisableByLoginType(...)` / `checkDisable(...)` / `checkDisableByLoginType(...)` / `getDisableTime(...)` / `getDisableTimeByLoginType(...)` | `LoginHelperTest` / `OnlineSessionController` |
| **二级认证** | 敏感操作二次安全窗 | `LoginHelper`<br>`@SaCheckSafe` | - | `openSafe([service,] Duration / int)` / `openSafeByLoginType(...)` / `isSafe([service])` / `isSafeByLoginType(...)` / `checkSafe([service])` / `checkSafeByLoginType(...)` / `closeSafe([service])` / `closeSafeByLoginType(...)` | `LoginHelperTest` / `AccessControlController` |
| **载荷与身份** | 强类型主体与租户剥离 | `LoginHelper`<br>`LoginUser` | `primary-login-type` | `setLoginUser([loginType,] LoginUser)` / `getLoginUser([loginType])` / `getLoginUser([loginType,] Class<T>)` / `getUserId([loginType])` / `getUserIdAsLong([loginType])` / `getRealUserIdAsLong([loginType])` / `getUserAccount([loginType])` / `getUserName([loginType])` / `getTenantId([loginType])` / `getDeptId([loginType])` / `buildCompositeLoginId(tenantId, userId)` | `LoginHelperTest` / `AuthSessionController` |
| **多账号体系** | 独立体系物理隔离 | `LoginHelper` | `primary-login-type` | `getStpLogic(String loginType)` / `registerStpLogic(String, StpLogic)` / `getPrimaryLoginType()` / `setPrimaryLoginType(String)` / `getActiveLoginType([preferredType])`，独立 `satoken-{loginType}` 请求头 | `LoginHelperMultiAccountTest` / `MultiAccountController` |
| **RBAC 鉴权** | Controller & Service 鉴权 | `@SaCheckRole`<br>`@SaCheckPermission`<br>`LoginHelper` | - | `getRoles([loginType])` / `getPermissions([loginType])` / `hasRole([loginType,] role)` / `hasPermission([loginType,] perm)` / `isSuperAdmin([loginType])` | `SampleSaTokenIntegrationTest` / `AccessControlController` |
| **越权防御** | 多账号水平越权防御 | `@CheckDataOwner`<br>`DataOwnerProvider<T>`<br>`LoginHelper` | `data-owner.enabled` | `@CheckDataOwner(expr, type, provider, allowAdmin, message)` / `DataOwnerProvider.getOwnerId(id, loginType)` / `checkSelf(...)` / `checkSelfByLoginType(...)` / `isSelf(...)` / `isSelfOrAdmin(...)` / `isBelongsToTenant(...)` / `checkBelongsToTenant(...)` / `checkBelongsToTenantByLoginType(...)` | `SaCheckDataOwnerAspectTest` / `AccessControlController` / `MultiAccountController` |
| **公开与白名单** | 多源免鉴权自动汇聚 | `@Anonymous`<br>`SaTokenWhitelistProvider`<br>`AnonymousUrlProcessor` | `white-list`<br>`interceptor.excludes` | 类/方法 `@Anonymous(boolean value = true)`（支持 `@Anonymous(true)` / `@Anonymous(false)`，扫描转译 `{id}`->`*`、`{*path}`->`**`）+ `SaTokenWhitelistProvider.getWhitelist()` + YAML 白名单去重聚合 | `AnonymousUrlProcessorTest` / `PublicPortalController` |
| **动态权限** | 动态 URL 权限规则管道 | `DynamicPathPermissionProvider`<br>`PathPermissionRule` | `interceptor.enabled`<br>`interceptor.check-login`<br>`interceptor.includes`<br>`interceptor.excludes` | `PathPermissionRule.of(...)`（支持 `httpMethod`、`loginType`、`SaMode.AND / SaMode.OR`），由 `AtengSaTokenInterceptor` 自动拦截匹配 | `PathPermissionRuleTest` / `AtengSaTokenInterceptorTest` / `PublicPortalController` |
| **路由拦截** | 双轨守卫与上下文清理 | `AtengSaTokenInterceptor`<br>`SaTokenWebMvcConfigurer` | `interceptor.enabled`<br>`interceptor.check-login`<br>`interceptor.includes`<br>`interceptor.excludes` | `preHandle` 校验并对称注入 `UserContextHolder` + `TenantContextHolder`，`afterCompletion` 在 `finally` 中双清空 | `AtengSaTokenInterceptorTest` / `SampleSaTokenCheckLoginIntegrationTest` |
| **两级缓存** | 多账号隔离权限缓存 | `CachedStpInterface`<br>`PermissionCacheService` | `permission-cache.enabled`<br>`permission-cache.timeout`<br>`permission-cache.empty-ttl` | 优先复用 `TwoLevelCacheManager`（`TwoLevelPermissionCacheAdapter.getTwoLevelCache()`，64 条带锁），回退轻量 Redis+5s LRU 或有界内存 LRU（10000 上限）；提供 `get` / `set` / `delete` / `build*Key` / `refreshUserPermissions([loginType,] userId)` / `refreshRolePermissions([loginType,] roleCode)` / `clearUserCache(...)` / `clearRoleCache(...)` / `clearAll()` / `clearAllCache()` / `clearLocalUserCache(...)` / `clearLocalRoleCache(...)` / `clearLocalAllCache()` / `getLocalCacheSize()` / `getAdapter()` | `CachedStpInterfaceTest` / `PermissionCacheServiceTest` / `PermissionCacheController` |
| **权限 SPI** | 角色权限业务加载扩展 | `UserRolePermissionLoader` | - | `loadRolesByUserId(userId[, loginType])` / `loadPermissionsByRole(roleCode[, loginType])` / `loadDirectPermissionsByUserId(userId[, loginType])`（默认实现 `DefaultUserRolePermissionLoader`） | `CachedStpInterfaceTest` / `PermissionCacheController` |
| **集群同步** | Pub/Sub 即时广播作废 | `SaTokenClusterBroadcaster`<br>`SaTokenClusterSyncListener` | `cluster-sync.enabled`<br>`cluster-sync.channel` | `broadcast(eventType, [loginType,] targetKey)` / `getInstanceId()` / `processRemoteMessage(msg)`，携带 `instanceId` 自消费防卫丢弃 | `SaTokenClusterSyncTest` / `InternalClusterController` |
| **防暴力破解** | 账号 + IP 双轨熔断 | `LoginFailureLockService` | `login-defense.enabled`<br>`login-defense.max-retry-count`<br>`login-defense.lock-time`<br>`login-defense.window-time`<br>`login-defense.ip-max-retry-count`<br>`login-defense.ip-lock-time`<br>`login-defense.ip-window-time`<br>`login-defense.ip-white-list` | `checkLocked(account[, clientIp])` / `checkIpLocked([clientIp])` / `isLocked(account[, clientIp])` / `isIpLocked(clientIp)` / `recordFailure(account[, clientIp])` / `recordIpFailure(clientIp)` / `getFailureCount` / `getRemainRetryCount` / `getLockTime` / `getDisableTime` / `getIpFailureCount` / `getIpRemainRetryCount` / `getIpLockTime` / `clearFailure` / `clearIpFailure` / `clearAllFailure` / `unlockIp` / `sniffClientIp()` / `extractClientIp` / `resolveClientIp` / `normalizeIp` / `isValidIp` / `isIpWhiteListed` | `LoginFailureLockServiceTest` / `LoginDefenseController` |
| **在线会话** | 增量游标在线用户治理 | `LoginHelper`<br>`OnlineUser` | - | `getOnlineUserCount([loginType])` / `searchOnlineUsers(...)` / `searchOnlineUsersByLoginType(...)` / `searchOnlineUserPage([loginType,] keyword, page, size)` / `searchOnlineUserPageByLoginType(...)` / `getOnlineUsers(...)`（SCAN 游标分批，关键字上限 500） | `LoginHelperOnlineUserTest` / `OnlineSessionController` |
| **身份模拟** | 双维模拟与原始人追踪 | `LoginHelper`<br>`SaTokenCurrentUserProvider` | - | `switchTo([loginType,] String / Long targetUserId)` / `endSwitch([loginType])` / `runAs(String / Long targetUserId, Supplier<T> / Runnable)` / `isRunAs([loginType])` / `getRealUserId([loginType])` / `getOriginalUserId([loginType])` | `LoginHelperTest` / `ImpersonateAuditController` |
| **上下文 SPI** | Core / 持久层审计贯通 | `SaTokenCurrentUserProvider`<br>`SaTokenCurrentTenantProvider` | - | 实现 Core `CurrentUserProvider` 与 `CurrentTenantProvider` SPI，自动贯通 MyBatis-Plus 审计字段填充与行级租户隔离 | `SampleSaTokenIntegrationTest` / `AuthSessionController` |
| **内部零信任** | Same-Token 防伪全闭环 | `@CheckSameToken`<br>`SameTokenClientHttpRequestInterceptor` | `same-token.enabled`<br>`same-token.header-name`<br>`same-token.token-value`<br>`same-token.auto-inject-client` | 无参标记注解 `@CheckSameToken`（切面触发 `MessageDigest.isEqual` 常数时间验签）；自动注入 `RestClientCustomizer` / `RestTemplateCustomizer` | `SaCheckSameTokenAspectTest` / `SameTokenClientHttpRequestInterceptorTest` / `InternalClusterController` |
| **安全事件** | Spring 原生事件总线桥接 | `SaTokenEventPublisher` | - | 桥接发布 8 类事件：`SaLoginEvent`、`SaLogoutEvent`、`SaKickoutEvent`、`SaReplacedEvent`、`SaDisableEvent`、`SaUntieDisableEvent`、`SaOpenSafeEvent`、`SaCloseSafeEvent` | `SaTokenEventPublisherTest` / `ImpersonateAuditController` |
| **分布式 DAO** | 纯 JSON 会话持久层 | `SaTokenRedisDao` | `ateng.boot.redis.enabled` | `get` / `set` / `update` / `delete` / `getTimeout` / `updateTimeout` / `searchData`（`ScanOptions.count(1000)`，纯 JSON 无 `@class`、`SET KEEPTTL`；并继承 `SaTokenDao` 提供的 `getObject` / `setObject` / `updateObject` / `deleteObject` / `getObjectTimeout` / `updateObjectTimeout`） | `SaTokenRedisDaoTest` / `SampleSaTokenIntegrationTest` |
| **OpenAPI** | Knife4j 全局鉴权弹窗 | `SaTokenOpenApiCustomizer` | `openapi.enabled`<br>`openapi.token-name` | 自动挂载 APIKey `SecurityScheme` 与全局 `SecurityRequirement` | `SaTokenOpenApiCustomizerTest` |
| **异常与防火墙** | 显式契约转译与防火墙 | `SaTokenExceptionHandler`<br>`SaFirewallStrategy` | - | 全量异常转译为 HTTP 200 + 401/403/400 `Result<Void>`；防火墙拦截返回 `Result.fail(400, ...)` | `SampleSaTokenIntegrationTest` |

---

## 1. 模块定位与极速起步 (Happy Path & Out-of-the-Box)

`ateng-boot-satoken` 基于 Sa-Token `1.46.0` 与 Spring Boot `3.3.13` 深度自适应构建，定位为轻量、零臃肿的分布式鉴权与会话治理底座：
- **显式强类型响应契约**：全域鉴权拦截与异常处理统一由 `SaTokenExceptionHandler` 返回 `Result<T>`，杜绝 `@ResponseBodyAdvice` 隐式包装与堆栈泄漏（参见 [ADR-0002](../docs/adr/0002-explicit-result-contract-no-response-body-advice.md)）；
- **全场景平滑降级**：检测到可用 Redis 时无缝接入纯净 JSON 分布式会话与集群同步；无 Redis 或关闭 `ateng.boot.redis.enabled: false` 时 100% 自动回退至内存/LRU 自洽运行；
- **全链路上下文透传**：自动注册 `CurrentUserProvider` 与 `CurrentTenantProvider`，实现从认证请求到 MyBatis-Plus 数据权限与自动审计的透明贯通。

### 1.1 Maven 依赖引入

#### 模式一：依托 BOM 统管引入（推荐，免写版本号）

在工程父 POM 或 `<dependencyManagement>` 已导入 `ateng-boot-bom`（参见 [ADR-0003](../docs/adr/0003-dependency-management-via-bom-import.md)）时，直接声明 Starter 坐标即可：

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-satoken</artifactId>
</dependency>
```

#### 模式二：独立直接引入（显式指定版本号）

若工程未导入 `ateng-boot-bom`，需显式指定当前基线版本号 `<version>1.2.0</version>`：

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-satoken</artifactId>
    <version>1.2.0</version>
</dependency>
```

> 按需可选增强组件：`ateng-boot-web`（驱动 `@Anonymous` 扫描与安全日志协同）、`ateng-boot-redis` / `spring-boot-starter-data-redis`（驱动 `SaTokenRedisDao`、`TwoLevelCacheManager` 64-条带权限缓存与 Redis Pub/Sub 集群同步）、`ateng-boot-mybatis-plus`（驱动审计字段与多租户透明填充）、`springdoc-openapi-starter-webmvc-ui`（驱动 Knife4j 自动鉴权弹窗）。

### 1.2 最小可用配置 (`application.yml`)

```yaml
ateng:
  boot:
    satoken:
      enabled: true               # 是否开启 ateng-boot-satoken 自动装配 (默认: true)
      token:
        timeout: 24h              # Token 绝对有效期，强类型 Duration (默认: 24h)
        active-timeout: 30m       # 空闲活跃超时，强类型 Duration (默认: 30m)
```

### 1.3 极速起步实战代码（源自 `AuthSessionController.java`）

```java
@Validated
@RestController
@RequestMapping("/sample/satoken/auth")
@RequiredArgsConstructor
public class AuthSessionController {

    @PostMapping("/login")
    @Anonymous
    public Result<String> login(@RequestBody LoginRequestDTO request) {
        String userId = (request != null && request.getUserId() != null && !request.getUserId().isBlank())
                ? request.getUserId()
                : (request != null && request.getUsername() != null ? "user-" + request.getUsername() : "user-1001");
        String username = (request != null && request.getUsername() != null && !request.getUsername().isBlank())
                ? request.getUsername()
                : userId;

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

    @GetMapping("/me")
    public Result<LoginUser> getLoginUser() {
        LoginHelper.checkLogin();
        return Result.success(LoginHelper.getLoginUser());
    }
}
```

### 1.4 预期响应输出

```json
// POST /sample/satoken/auth/login 成功响应
{
  "code": 200,
  "msg": "操作成功",
  "data": "5a9f2d8e-7c41-4c72-9b1f-3d841a8c0e11",
  "success": true
}

// GET /sample/satoken/auth/me（携带请求头 satoken: <TOKEN>）
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "userId": "10086",
    "userAccount": "admin",
    "userName": "admin",
    "userType": "default",
    "tenantId": "tenant_sz",
    "roles": ["audit_manager"],
    "permissions": ["order:query"]
  },
  "success": true
}
```

---

## 2. 核心架构与设计不变量 (Core Architecture & Invariants)

```text
┌──────────────────────────────────────────────────────────────────────────────────┐
│                        HTTP 请求 / Spring RestClient 出站链路                      │
└────────────────────────────────────────┬─────────────────────────────────────────┘
                                         │
        ┌────────────────────────────────┴────────────────────────────────┐
        ▼                                                                 ▼
┌─────────────────────────────────┐                     ┌──────────────────────────────────┐
│     AtengSaTokenInterceptor     │                     │   AOP 切面体系 (Controller/Service)│
│ • 白名单 O(1)/AntPath 快速放行   │                     │ • @SaCheckRole / @SaCheckPermission│
│ • DynamicPathPermission SPI 鉴权 │                     │ • @CheckDataOwner (多账号 IDOR 防御)│
│ • 四维上下文对称注入与 finally 清理│                     │ • @CheckSameToken (零信任常数时间验签)│
└────────────────┬────────────────┘                     └────────────────┬─────────────────┘
                 │                                                       │
                 └───────────────────────┬───────────────────────────────┘
                                         ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                    ateng-boot-satoken 核心门面与治理引擎 (ReentrantLock)          │
├────────────────────────────────┬─────────────────────────────────────────────────┤
│ • LoginHelper (多账号/续签/强退) │ • CachedStpInterface / PermissionCacheService    │
│ • TempTokenHelper (短效业务令牌) │   (TwoLevelCacheManager / 5s LRU / 有界内存)    │
│ • LoginFailureLockService      │ • SaTokenClusterBroadcaster (Redis Pub/Sub)     │
│ • OnlineUser (SCAN 游标采集 IP)  │ • SaTokenEventPublisher (8 类 Spring 安全事件)   │
└────────────────┬───────────────┴───────────────────────────────┬─────────────────┘
                 │                                               │
                 ▼                                               ▼
┌─────────────────────────────────┐             ┌──────────────────────────────────┐
│     Core / 持久层生态透传        │             │       自适应存储驱动与平滑降级      │
├─────────────────────────────────┤             ├──────────────────────────────────┤
│ CurrentUserProvider (支持 RunAs) │             │ 有 Redis: SaTokenRedisDao (纯JSON)│
│ CurrentTenantProvider -> MP 隔离│             │ 无 Redis: SaTokenDaoDefault (内存) │
└─────────────────────────────────┘             └──────────────────────────────────┘
```

### 核心设计六道不变量 (The 6 Invariants)

1. **显式响应契约 (Explicit Result Contract)**：全量安全异常与防火墙拦截统一转译为 HTTP 200 + `Result<Void>`（401 / 403 / 400），绝不穿透栈痕信息（[ADR-0002](../docs/adr/0002-explicit-result-contract-no-response-body-advice.md)）；
2. **严格无状态与虚拟线程防 Pinning (Statelessness & ReentrantLock)**：所有公共单例保持无状态；涉及懒加载与并发控制的临界区统一使用 `ReentrantLock` 替代 `synchronized`，杜绝 JDK 21+ 虚拟线程 Carrier Pinning（[ADR-0029](../docs/adr/0029-virtual-threads-and-adaptive-async-runtime.md)）；
3. **内存真明文不变量 (In-Memory Plaintext Invariant)**：会话载荷 `LoginUser` 在内存中保持 100% 原始明文，展示脱敏收敛于 Jackson 与导出层（[ADR-0012](../docs/adr/0012-lifecycle-separated-data-security.md)）；
4. **四维上下文对称生命周期 (Symmetric Context Lifecycle)**：`AtengSaTokenInterceptor.preHandle` 对称写入 `UserContextHolder` 与 `TenantContextHolder`，`afterCompletion` 在 `finally` 块中强制执行双清空；
5. **纯内存确定性平滑降级 (Zero-Mock Invariant)**：无 Redis 或 `ateng.boot.redis.enabled: false` 时，会话持久层、两级权限缓存、集群广播与防爆破计数 100% 自动降级为有界本地实现；
6. **防御性自动装配四铁律 (Defensive Autoconfiguration)**：全量装配注册于标准 Spring Boot 3 `AutoConfiguration.imports` 文件，标配条件守卫与 `@ConditionalOnMissingBean`，多账号体系共享统一 `ReentrantLock` 懒加载注册机制。

---

## 3. 会话认证、有效期续签与短效临时凭据

### 场景痛点
1. **原生标量会话类型脆弱**：原生 `StpUtil.login(id)` 仅保存标量标识，读取姓名、租户、部门、角色集往往需手工拆串或强制转换 `Map`；
2. **固定会话无法动态续期与按端下线**：长流程操作或活跃用户需要精确延长指定 Token/会话有效期，多端在线需支持仅注销当前设备；
3. **短效业务凭据污染主会话或携带 `@class`**：文件私有下载短链、扫码确认、邮件激活等一次性/短效场景，直接复用主认证 Token 存在越权风险，而传统序列化易残留脆弱的 `@class` 类型标签。

### 3.1 专属 YAML 配置片段

```yaml
ateng:
  boot:
    satoken:
      enabled: true
      token:
        timeout: 24h              # Token 绝对有效期，强类型 Duration (默认: 24h)
        active-timeout: 30m       # 空闲活跃超时，强类型 Duration (默认: 30m；<=0 表示关闭)
        is-concurrent: true       # 允许同一账号并发多端登录 (默认: true)
        is-share: true            # 同端多次登录是否共用同一 Token (默认: true)
        max-login-count: 12       # is-concurrent=true 且 is-share=false 时的最大并发展开数 (默认: 12)
        token-style: uuid         # Token 生成算法风格: uuid/simple-uuid/random-32/random-64/tik (默认: uuid)
```

> 说明：`AtengSaTokenAutoConfiguration` 启动时会把 `ateng.boot.satoken.token.*` 强类型属性双向桥接写入 Sa-Token 全局 `SaManager.getConfig()`，免除秒数换算心智负担；同时登录时自动嗅探客户端 IP 写入 `terminalExtra("loginIp")`，为在线会话监控提供真实终端溯源。

### 3.2 1:1 实战代码（源自 `AuthSessionController.java`）

```java
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/satoken/auth")
@RequiredArgsConstructor
public class AuthSessionController {

    /**
     * 主动续期当前登录会话 Token 剩余有效时间（支持主体系与多账号 loginType）
     */
    @PostMapping("/renew-timeout")
    public Result<Long> renewTimeout(
            @RequestParam(value = "timeoutSeconds", defaultValue = "1800")
            @Positive(message = "续期时间必须大于 0 秒") long timeoutSeconds,
            @RequestParam(value = "loginType", required = false) String loginType) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        if (loginType != null && !loginType.isBlank()) {
            LoginHelper.checkLogin(loginType);
            LoginHelper.renewTimeout(loginType, timeout);
            return Result.success(LoginHelper.getTokenTimeout(loginType));
        }
        LoginHelper.checkLogin();
        LoginHelper.renewTimeout(timeout);
        return Result.success(LoginHelper.getTokenTimeout());
    }

    /**
     * 签发强类型短效临时业务令牌（零 @class 纯净 JSON 存储）
     */
    @PostMapping("/temp-token/issue")
    public Result<TempTokenVO> issueTempToken(@Valid @RequestBody TempTokenIssueDTO request) {
        LoginHelper.checkLogin();
        long ttlSeconds = (request.getTimeoutSeconds() != null && request.getTimeoutSeconds() > 0)
                ? request.getTimeoutSeconds() : 300L;
        TempTokenTicketVO payload = TempTokenTicketVO.builder()
                .bizScene(request.getBizScene().trim())
                .resourceId(request.getResourceId().trim())
                .resourceName(request.getResourceName())
                .requesterId(LoginHelper.getUserId())
                .build();
        String tempToken = TempTokenHelper.createTempToken(payload, Duration.ofSeconds(ttlSeconds));
        long remainTtl = TempTokenHelper.getTempTokenTimeout(tempToken);
        return Result.success(TempTokenVO.builder()
                .token(tempToken)
                .ttlSeconds(remainTtl)
                .payload(payload)
                .build());
    }

    /**
     * 匿名核验临时业务令牌并还原强类型业务载荷
     */
    @GetMapping("/temp-token/verify")
    @Anonymous
    public Result<TempTokenVO> verifyTempToken(
            @NotBlank(message = "临时令牌不能为空") @RequestParam("token") String token) {
        TempTokenTicketVO payload = TempTokenHelper.parseTempToken(token, TempTokenTicketVO.class);
        if (payload == null) {
            return Result.fail(CommonResultCode.UNAUTHORIZED.getCode(), "临时业务令牌无效或已过期");
        }
        long remainTtl = TempTokenHelper.getTempTokenTimeout(token);
        return Result.success(TempTokenVO.builder()
                .token(token.trim())
                .ttlSeconds(remainTtl)
                .payload(payload)
                .build());
    }

    /**
     * 主动销毁临时业务令牌（实现阅后即焚一次性消费闭环）
     */
    @DeleteMapping("/temp-token/revoke")
    public Result<Void> revokeTempToken(
            @NotBlank(message = "临时令牌不能为空") @RequestParam("token") String token) {
        LoginHelper.checkLogin();
        TempTokenHelper.deleteTempToken(token);
        return Result.success();
    }

    /**
     * 指定设备端注销 / 当前会话注销
     */
    @PostMapping("/logout-device")
    public Result<Void> logoutDevice(@RequestParam("device") String device) {
        LoginHelper.logoutByDevice(device);
        return Result.success();
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        LoginHelper.logout();
        return Result.success();
    }
}
```

### 3.3 预期输出与状态变化

```json
// 1. POST /sample/satoken/auth/renew-timeout?timeoutSeconds=3600
{
  "code": 200,
  "msg": "操作成功",
  "data": 3600,
  "success": true
}

// 2. POST /sample/satoken/auth/temp-token/issue
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "token": "7c2f1a904e5d48e1a6b3e1120f9c88a1",
    "ttlSeconds": 300,
    "payload": {
      "bizScene": "FILE_DOWNLOAD",
      "resourceId": "doc-2026",
      "resourceName": "季度审计报告.pdf",
      "requesterId": "10086"
    }
  },
  "success": true
}

// 3. DELETE /temp-token/revoke 后再次 GET /temp-token/verify（阅后即焚已失效）
{
  "code": 401,
  "msg": "临时业务令牌无效或已过期",
  "data": null,
  "success": false
}
```

> 补充门面：`LoginHelper` 同时提供按指定 Token 续期方法 `renewTimeoutByToken(tokenValue, Duration)` 及 `renewTimeoutByToken(loginType, tokenValue, Duration)`，以及 `TempTokenHelper` 全量静态委托入口。

---

## 4. 多账号体系隔离与多租户安全全生命周期

### 场景痛点
1. **多端会话踩踏与 Header 冲突**：管理后台（`admin`）与商城会员端（`member`）共享默认 `StpUtil` 与 `satoken` 请求头时，相同数字主键会导致异端互相踢下线或越权混读；
2. **多租户复合主键繁琐且易泄露**：SaaS 体系需用 `{tenantId}_{userId}` 做底层会话隔离，但业务与 MyBatis-Plus 审计字段期望纯净的业务 `userId`；
3. **ORM 实体直接暴露**：Controller 若直接收发持久层 Entity，极易泄露敏感审计与逻辑删除字段。

### 4.1 专属 YAML 配置片段

```yaml
sa-token:
  token-name: satoken             # 主账号体系使用请求头 satoken；独立体系自动映射为 satoken-{loginType}（如 satoken-admin / satoken-member）
  is-concurrent: true
  is-share: false

ateng:
  boot:
    satoken:
      primary-login-type: login   # 多账号共存时优先映射至 UserContextHolder 的主账号体系 (默认: login)
```

### 4.2 1:1 实战代码（源自 `MultiAccountController.java` 与 `AuthSessionController.java`）

```java
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/satoken/multi-account")
@RequiredArgsConstructor
public class MultiAccountController {

    /**
     * 管理端独立登录（下发独立请求头 satoken-admin）
     */
    @PostMapping("/admin/login")
    public Result<String> adminLogin(@RequestBody LoginRequestDTO request) {
        String userId = (request != null && request.getUserId() != null && !request.getUserId().isBlank())
                ? request.getUserId() : "admin-001";
        String username = (request != null && request.getUsername() != null && !request.getUsername().isBlank())
                ? request.getUsername() : userId;

        LoginUser loginUser = LoginUser.builder()
                .userId(userId)
                .userAccount(username)
                .userName(username)
                .tenantId(request != null ? request.getTenantId() : null)
                .roles(request != null && request.getRoles() != null ? request.getRoles() : Set.of("super_admin"))
                .permissions(request != null && request.getPermissions() != null ? request.getPermissions() : Set.of("system:manage"))
                .build();

        LoginHelper.login("admin", loginUser);
        return Result.success(LoginHelper.getTokenValue("admin"));
    }

    /**
     * 管理端专属角色鉴权（必须携带 admin 体系 Token）
     */
    @GetMapping("/admin/manage-roles")
    @SaCheckRole(type = "admin", value = "super_admin")
    public Result<String> checkAdminManageRoles() {
        return Result.success("管理员特权接口校验通过");
    }

    /**
     * 会员端独立登录（下发独立请求头 satoken-member，与 admin 物理隔离）
     */
    @PostMapping("/member/login")
    public Result<String> memberLogin(@RequestBody LoginRequestDTO request) {
        String userId = (request != null && request.getUserId() != null && !request.getUserId().isBlank())
                ? request.getUserId() : "member-001";
        String username = (request != null && request.getUsername() != null && !request.getUsername().isBlank())
                ? request.getUsername() : userId;

        LoginUser loginUser = LoginUser.builder()
                .userId(userId)
                .userAccount(username)
                .userName(username)
                .tenantId(request != null ? request.getTenantId() : null)
                .roles(request != null && request.getRoles() != null ? request.getRoles() : Set.of("vip_member"))
                .permissions(request != null && request.getPermissions() != null ? request.getPermissions() : Set.of("member:points:view"))
                .build();

        LoginHelper.login("member", loginUser);
        return Result.success(LoginHelper.getTokenValue("member"));
    }

    @GetMapping("/member/points")
    @SaCheckPermission(type = "member", value = "member:points:view")
    public Result<String> checkMemberPoints() {
        return Result.success("会员积分特权接口校验通过");
    }
}
```

多租户透明剥离与 MyBatis-Plus 自动审计（源自 `AuthSessionController.java`）：

```java
    @GetMapping("/user-id")
    public Result<String> getUserId() {
        LoginHelper.checkLogin();
        // 底层会话以 "{tenantId}_{userId}" 存储时，getUserId() 自动透明剥离前缀仅返回纯净业务 userId
        return Result.success(LoginHelper.getUserId());
    }

    @GetMapping("/current-tenant")
    public Result<String> getCurrentTenant() {
        LoginHelper.checkLogin();
        return Result.success(TenantContextHolder.getTenantId());
    }

    @PostMapping("/orders")
    public Result<Long> createOrder(@Valid @RequestBody OrderCreateDTO orderDto) {
        // 触发 SaTokenCurrentUserProvider / SaTokenCurrentTenantProvider 自动填充 createBy 与 tenantId
        Long orderId = sampleService.createOrder(orderDto.getOrderNo(), orderDto.getAmount());
        return Result.success(orderId);
    }

    @GetMapping("/orders/{orderId}")
    public Result<TenantOrderVO> getOrder(@PathVariable("orderId") Long orderId) {
        TenantOrderVO order = sampleService.getOrderById(orderId);
        if (order == null) {
            return Result.fail(CommonResultCode.NOT_FOUND.getCode(), "订单不存在");
        }
        return Result.success(order);
    }
```

### 4.3 预期响应输出与机制要点

```json
// GET /sample/satoken/auth/orders/1（严格遵循 Zero-Entity-Leakage，返回 TenantOrderVO；实际 Result 外层还包含 timestamp 与 traceId 字段）
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "id": 1,
    "orderNo": "ORD-20261108-01",
    "amount": 199.90,
    "tenantId": "tenant_sz",
    "createBy": "10086",
    "createTime": "2026-11-13 10:00:00",
    "updateBy": "10086",
    "updateTime": "2026-11-13 10:00:00"
  },
  "success": true
}

// 携带 member Token 访问 /sample/satoken/multi-account/admin/manage-roles（跨账号体系隔离阻断）
{
  "code": 401,
  "msg": "未提供访问凭据 (Token)",
  "data": null,
  "success": false
}
```

> 若系统需要定制特定 `loginType` 的前缀规则或独立配置，可在应用初始化时调用 `LoginHelper.registerStpLogic("open", customStpLogic)` 显式预注册；未预注册的 `loginType` 会在首次调用 `LoginHelper.getStpLogic(loginType)` 时通过 `ReentrantLock` 并发安全懒加载创建并设置独立 Cookie/Header 名 `satoken-{loginType}`。

---

## 5. 细粒度访问控制、二级认证与水平越权防御 (IDOR)

### 场景痛点
1. **Service 内层缺乏方法级鉴权防线**：仅在网关或 Controller 做拦截，业务 Service 被跨模块调用时仍可能越权；
2. **高危操作缺少时效性二次验证**：大额结算、敏感改密等场景凭据一旦被盗即引发资金损失；
3. **BOLA / IDOR 水平越权漏洞与多账号盲区**：用户篡改 `userId` 或 `orderId` 操作他人数据时，手工校验极易遗漏，且传统校验注解只认默认 `login` 体系，在 `member` 多账号体系下失效。

### 5.1 专属 YAML 配置片段

```yaml
ateng:
  boot:
    satoken:
      data-owner:
        enabled: true             # 是否启用 @CheckDataOwner 水平越权防御 AOP 切面 (默认: true)
```

### 5.2 1:1 实战代码（源自 `AccessControlController.java`、`MultiAccountController.java` 与 `SampleOrderDataOwnerProvider.java`）

```java
@Validated
@RestController
@RequestMapping("/sample/satoken/access")
@RequiredArgsConstructor
public class AccessControlController {

    private final SaTokenSampleService sampleService;

    /**
     * 1. RBAC 注解鉴权（OR 逻辑 / Service 穿透）
     */
    @GetMapping("/role-audit")
    @SaCheckRole(value = {"audit_manager", "finance_director"}, mode = SaMode.OR)
    public Result<String> checkRoleAudit() {
        return Result.success("审计角色鉴权通过");
    }

    @GetMapping("/service-permission")
    public Result<String> checkServicePermission() {
        // 穿透调用标注 @SaCheckPermission("order:cancel") 的 Service 方法
        return Result.success(sampleService.checkServicePermission());
    }

    /**
     * 2. 二级安全认证时间窗与敏感业务双模式校验
     */
    @PostMapping("/open-safe")
    public Result<Integer> openSafe(@RequestParam(value = "timeout", defaultValue = "120") int timeout) {
        LoginHelper.openSafe(timeout);
        return Result.success(timeout);
    }

    @PostMapping("/safe-settle-anno")
    @SaCheckSafe
    public Result<String> safeSettleDeclarative(@Valid @RequestBody SettlementRequestDTO dto) {
        return Result.success("大额资金结算已完成 (声明式): " + dto.getAmount());
    }

    /**
     * 3. @CheckDataOwner 直等 SpEL 与 SPI 资源反查防水平越权
     */
    @PostMapping("/profile/update")
    @CheckDataOwner(expr = "#dto.userId")
    public Result<String> updateProfile(@Valid @RequestBody UserProfileDTO dto) {
        return Result.success("个人资料更新成功: " + dto.getNickname());
    }

    @PostMapping("/orders/{orderId}/cancel")
    @CheckDataOwner(expr = "#orderId", provider = SampleOrderDataOwnerProvider.class)
    public Result<String> cancelOrder(@PathVariable("orderId") Long orderId) {
        return Result.success("订单已成功取消: " + orderId);
    }

    /**
     * 4. 编程式本人与租户归属强断言
     */
    @PostMapping("/assert-self")
    public Result<String> assertSelf(@RequestParam("userId") String userId) {
        LoginHelper.checkSelf(userId);
        return Result.success("本人所有权断言校验通过: " + userId);
    }
}
```

多账号体系下的显式 `@CheckDataOwner` 与 SPI 反查：

```java
// 1. MultiAccountController.java：显式指定 type = "member" 对齐会员账号体系
@PostMapping("/member/profile/update")
@CheckDataOwner(type = "member", expr = "#dto.userId")
public Result<String> updateMemberProfile(@Valid @RequestBody UserProfileDTO dto) {
    return Result.success("会员资料更新成功: " + dto.getNickname());
}

// 2. SampleOrderDataOwnerProvider.java：实现 DataOwnerProvider<Long> SPI 反查真实归属人
@Component
public class SampleOrderDataOwnerProvider implements DataOwnerProvider<Long> {

    private static final Map<Long, String> MOCK_ORDER_OWNER_DB = Map.of(
            88001L, "10086",
            88002L, "20001"
    );

    @Override
    public String getOwnerId(Long resourceId) {
        return MOCK_ORDER_OWNER_DB.get(resourceId);
    }
}
```

### 5.3 预期输出与关键机制

```json
// 本人 (userId="10086") 取消自己的订单 88001
{
  "code": 200,
  "msg": "操作成功",
  "data": "订单已成功取消: 88001",
  "success": true
}

// 用户 "10086" 企图取消属于 "20001" 的订单 88002（@CheckDataOwner 水平越权阻断）
{
  "code": 403,
  "msg": "无权访问该资源",
  "data": null,
  "success": false
}

// 未调用 /open-safe 直接请求 @SaCheckSafe 接口
{
  "code": 403,
  "msg": "无权访问：当前操作需要二级安全认证",
  "data": null,
  "success": false
}
```

> 多账号感知解析顺序：`SaCheckDataOwnerAspect` 优先读取 `@CheckDataOwner(type = "...")`；当 `type` 为空时，自动通过 `LoginHelper.getActiveLoginType()` 嗅探当前请求活跃账号体系，并将 `loginType` 透传给 `DataOwnerProvider.getOwnerId(resource, loginType)` 与 `LoginHelper.isSelf(loginType, ownerId)`；当 `allowAdmin = true` 且 `LoginHelper.isSuperAdmin(loginType)` 为 `true` 时豁免放行。

---

## 6. 多账号两级权限缓存与分布式集群即时同步

### 场景痛点
1. **鉴权频繁穿透数据库**：高频调用 `@SaCheckPermission` 若每次直接查库或裸查 Redis，将打垮后端存储；
2. **多账号相同 `userId`/`roleCode` 缓存串染**：若 `admin` 与 `member` 共用同一缓存键 `user-roles:{userId}`，会导致两套账号体系权限互相覆盖；
3. **节点局部缓存脏读与回环广播**：节点 A 刷新权限后，节点 B 本地微缓存未驱逐造成已降权员工越权；广播时本机重复消费自身消息引发风暴。

### 6.1 专属 YAML 配置片段

```yaml
ateng:
  boot:
    satoken:
      permission-cache:
        enabled: true               # 是否开启两级权限与角色缓存 (默认: true)
        timeout: 5m                 # 权限与角色缓存基础有效期，强类型 Duration (默认: 5m；写缓存附加 +10%~+20% 动态 Jitter)
        empty-ttl: 60s              # 空角色/空权限防穿透哨兵 (__EMPTY__) 有效期，强类型 Duration (默认: 60s)
      cluster-sync:
        enabled: true               # 是否开启基于 Redis Pub/Sub 的集群同步广播 (默认: true)
        channel: "ateng:satoken:cluster-sync" # Redis Pub/Sub 广播频道名称 (默认: ateng:satoken:cluster-sync)
```

### 6.2 1:1 实战代码（源自 `PermissionCacheController.java` 与 `InternalClusterController.java`）

```java
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/satoken/permission-cache")
@RequiredArgsConstructor
public class PermissionCacheController {

    private final PermissionCacheService permissionCacheService;

    /**
     * 读取当前主体经两级缓存解析的角色列表（支持按 loginType 命名空间隔离查询）
     */
    @GetMapping("/roles")
    public Result<Set<String>> getCurrentRoles(@RequestParam(value = "loginType", required = false) String loginType) {
        String targetType = (loginType != null && !loginType.isBlank()) ? loginType.trim() : StpUtil.TYPE;
        LoginHelper.checkLogin(targetType);
        List<String> roleList = LoginHelper.getStpLogic(targetType).getRoleList();
        return Result.success(roleList != null ? Set.copyOf(roleList) : Collections.emptySet());
    }

    /**
     * 读取当前主体经两级缓存解析的权限码集合（支持按 loginType 命名空间隔离查询）
     */
    @GetMapping("/permissions")
    public Result<Set<String>> getCurrentPermissions(@RequestParam(value = "loginType", required = false) String loginType) {
        String targetType = (loginType != null && !loginType.isBlank()) ? loginType.trim() : StpUtil.TYPE;
        LoginHelper.checkLogin(targetType);
        List<String> permissionList = LoginHelper.getStpLogic(targetType).getPermissionList();
        return Result.success(permissionList != null ? Set.copyOf(permissionList) : Collections.emptySet());
    }

    /**
     * 精准刷新单角色权限缓存（本地驱逐 + 通过 SaTokenClusterBroadcaster 广播异地节点）
     */
    @PostMapping("/refresh-role")
    public Result<String> refreshRole(@RequestParam("roleCode") String roleCode) {
        permissionCacheService.refreshRolePermissions(roleCode);
        return Result.success("角色权限缓存已刷新: " + roleCode);
    }

    /**
     * 精准刷新单用户权限缓存（默认体系；多账号可调用 refreshUserPermissions(loginType, userId)）
     */
    @PostMapping("/refresh-user")
    public Result<String> refreshUser(@RequestParam("userId") String userId) {
        permissionCacheService.refreshUserPermissions(userId);
        return Result.success("用户权限缓存已刷新: " + userId);
    }

    /**
     * 全网清空两级权限缓存
     */
    @PostMapping("/clear-all")
    public Result<Void> clearAll() {
        permissionCacheService.clearAll();
        return Result.success();
    }
}
```

集群实例标识与广播触发端点（源自 `InternalClusterController.java`）：

```java
    @GetMapping("/sample/satoken/cluster/instance-id")
    @Anonymous
    public Result<String> getInstanceId() {
        return Result.success(broadcaster.getInstanceId());
    }

    @PostMapping("/sample/satoken/cluster/refresh-user")
    @Anonymous
    public Result<Void> refreshUserPermissions(@RequestParam("userId") String userId) {
        permissionCacheService.refreshUserPermissions(userId);
        return Result.success();
    }
```

### 6.3 预期输出与核心机制说明

```json
// GET /sample/satoken/permission-cache/roles?loginType=admin
{
  "code": 200,
  "msg": "操作成功",
  "data": ["super_admin"],
  "success": true
}

// POST /sample/satoken/permission-cache/refresh-user?userId=10086
{
  "code": 200,
  "msg": "操作成功",
  "data": "用户权限缓存已刷新: 10086",
  "success": true
}
```

1. **三级自适应缓存适配器（`ReentrantLock` 懒加载）**：
   - 存在 `ateng-boot-redis` 的 `TwoLevelCacheManager` 且 Redis 开启时：自动委托给 `TwoLevelPermissionCacheAdapter`（空间 `"satoken:permissions"`，复用 64-条带并发锁与 L1/L2 级联缓存）；
   - 仅存在 `StringRedisTemplate` 且 Redis 开启时：降级为 `RedisPermissionCacheAdapter`（Redis L2 + 本地最长 5 秒的线程安全 LRU 微缓存）；
   - 无 Redis 或 `ateng.boot.redis.enabled: false` 时：平滑降级为 `InMemoryPermissionCacheAdapter`（有界 10000 条本地 LRU）。
2. **多账号向后兼容键隔离**：默认 `"login"` 体系保持 `ateng:satoken:cache:user-roles:{userId}`、`role-perms:{roleCode}`、`user-perms:{userId}`；非默认体系自动升级为 `ateng:satoken:cache:{loginType}:{segment}:{id}`，且 `PermissionCacheService` 与 `SaTokenClusterBroadcaster` 均提供 `(loginType, targetKey)` 重载以实现单体系精准失效。
3. **集群 Pub/Sub 自消费防卫**：每个节点持有一个无锁 `fastSimpleUUID()` 生成的 `instanceId`；`SaTokenClusterSyncListener` 收到 `SaTokenClusterMessage` 后先校验 `senderInstanceId.equals(broadcaster.getInstanceId())`，命中本机则直接静默丢弃，避免回环风暴。

---

## 7. 客户端 IP 自动嗅探与双轨防爆破熔断 (Dual-Track Brute Force Defense)

### 场景痛点
恶意攻击者除对单一账号发起密码字典爆破外，还会从单攻击 IP 对海量账号并发“撞库”。若只锁账号易造成合法账号大面积拒绝服务（DoS）；若无差别封禁 IP，又可能误伤使用统一 NAT 出口的企业内网员工。

### 7.1 专属 YAML 配置片段

```yaml
ateng:
  boot:
    satoken:
      login-defense:
        enabled: true             # 是否开启登录防暴力破解策略器（默认: false，按需显式开启）
        max-retry-count: 5        # 单账号最大允许连续失败次数 (默认: 5)
        lock-time: 15m            # 账号触发锁定后的封禁时长，强类型 Duration (默认: 15m)
        window-time: 15m          # 账号失败计数滑动统计窗口，强类型 Duration (默认: 15m)
        ip-max-retry-count: 30    # 单客户端 IP 最大允许连续失败次数 (默认: 30)
        ip-lock-time: 30m         # 客户端 IP 触发熔断后的锁定时长，强类型 Duration (默认: 30m)
        ip-window-time: 15m       # 客户端 IP 失败计数滑动统计窗口，强类型 Duration (默认: 15m)
        ip-white-list:            # IP 豁免白名单（支持精确 IPv4/IPv6 与 CIDR 网段如 10.0.0.0/8）
          - "127.0.0.1"
          - "0:0:0:0:0:0:0:1"
          - "::1"
```

### 7.2 1:1 实战代码（源自 `LoginDefenseController.java`）

```java
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/satoken/defense")
@RequiredArgsConstructor
public class LoginDefenseController {

    private final LoginFailureLockService lockService;

    /**
     * 防爆破安全登录接口：支持账号 + 客户端真实 IP 双轨滑动统计与 Fail-Fast 阻断
     */
    @PostMapping("/login")
    public Result<String> login(@Valid @RequestBody DefenseLoginRequestDTO request) {
        String account = request.getAccount();
        // 支持显式传入或由 lockService.sniffClientIp() 自动嗅探真实客户端 IP（规范化 ::1 为 127.0.0.1）
        String clientIp = lockService.resolveClientIp(request.getClientIp());

        // 1. 前置 Fail-Fast：账号锁定或非白名单 IP 熔断直接拦截
        lockService.checkLocked(account, clientIp);

        // 2. 密码校验示例（约定正确密码为 123456）
        if (!"123456".equals(request.getPassword())) {
            int remain = lockService.recordFailure(account, clientIp);
            if (remain == 0) {
                throw new BusinessException(CommonResultCode.FORBIDDEN, "登录失败次数过多，已被锁定，请稍后再试");
            }
            throw new BusinessException(CommonResultCode.UNAUTHORIZED, "账号或密码错误，剩余重试机会: " + remain + " 次");
        }

        // 3. 登录成功，对称清零账号与客户端 IP 失败计数
        lockService.clearAllFailure(account, clientIp);

        LoginUser loginUser = LoginUser.builder()
                .userId("user-" + account)
                .userAccount(account)
                .userName(account)
                .build();
        LoginHelper.login(loginUser);
        return Result.success(LoginHelper.getTokenValue());
    }

    @GetMapping("/account-status")
    public Result<AccountDefenseStatusVO> getAccountStatus(@RequestParam("account") String account) {
        return Result.success(AccountDefenseStatusVO.builder()
                .account(account)
                .locked(lockService.isLocked(account))
                .failureCount(lockService.getFailureCount(account))
                .remainRetryCount(lockService.getRemainRetryCount(account))
                .lockTime(lockService.getLockTime(account))
                .build());
    }

    @GetMapping("/ip-status")
    public Result<IpDefenseStatusVO> getIpStatus(@RequestParam(value = "clientIp", required = false) String clientIp) {
        String resolvedIp = lockService.resolveClientIp(clientIp);
        return Result.success(IpDefenseStatusVO.builder()
                .clientIp(resolvedIp)
                .locked(lockService.isIpLocked(resolvedIp))
                .failureCount(lockService.getIpFailureCount(resolvedIp))
                .remainRetryCount(lockService.getIpRemainRetryCount(resolvedIp))
                .lockTime(lockService.getIpLockTime(resolvedIp))
                .whiteListed(lockService.isIpWhiteListed(resolvedIp))
                .build());
    }

    @PostMapping("/unlock-account")
    public Result<String> unlockAccount(@RequestParam("account") String account) {
        lockService.clearFailure(account);
        LoginHelper.untieDisable(account);
        return Result.success("账号已解锁: " + account);
    }

    @PostMapping("/unlock-ip")
    public Result<String> unlockIp(@RequestParam("clientIp") String clientIp) {
        lockService.unlockIp(clientIp);
        lockService.clearIpFailure(clientIp);
        return Result.success("客户端 IP 已解除熔断: " + clientIp);
    }
}
```

### 7.3 预期输出与双轨防护规则

```json
// 1. 密码输错第 1 次失败响应（HTTP 200 + 业务码 401）
{
  "code": 401,
  "msg": "账号或密码错误，剩余重试机会: 4 次",
  "data": null,
  "success": false
}

// 2. GET /sample/satoken/defense/account-status?account=audit_operator_01（连续输错被锁后）
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "account": "audit_operator_01",
    "locked": true,
    "failureCount": 5,
    "remainRetryCount": 0,
    "lockTime": 900
  },
  "success": true
}

// 3. GET /sample/satoken/defense/ip-status?clientIp=198.51.100.66 与非白名单 IP 触发 ip-max-retry-count 熔断后再次登录尝试
{
  "code": 403,
  "msg": "客户端 IP 连续登录失败次数超限，已被临时锁定",
  "data": null,
  "success": false
}
```

> 自动嗅探与无 Redis 降级机制：`LoginFailureLockService.sniffClientIp()` 依次尝试读取 `SaHolder.getRequest()` 与 Spring Web `RequestContextHolder` 的 `X-Forwarded-For`、`X-Real-IP`、`Proxy-Client-IP`、`WL-Proxy-Client-IP`、`HTTP_CLIENT_IP`、`HTTP_X_FORWARDED_FOR` 与 `remoteAddr`，并将 `0:0:0:0:0:0:0:1`/`::1` 规范化为 `127.0.0.1`；无 Redis 时回退至带 TTL 时间戳与 `10_000` 上限的有界 `ConcurrentHashMap`。

---

## 8. 在线会话安全游标监控与精准强退治理 (Online User Governance)

### 场景痛点
运营与安全审计后台需要统计集群在线人数、按账号体系或关键字检索会话设备、IP 与剩余寿命，并按指定 Token、指定设备端或封禁时长进行精确处置，且绝不能因全量拉取会话导致 Redis 阻塞或应用 OOM。

### 8.1 专属 YAML 配置片段

```yaml
ateng:
  boot:
    satoken:
      enabled: true
      token:
        timeout: 24h              # 会话基础有效期
        is-concurrent: true       # 允许同一账号多终端并行在线
```

### 8.2 1:1 实战代码（源自 `OnlineSessionController.java`）

```java
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/satoken/online")
@RequiredArgsConstructor
public class OnlineSessionController {

    @GetMapping("/count")
    public Result<Long> getOnlineCount(@RequestParam(value = "loginType", required = false) String loginType) {
        if (loginType != null && !loginType.isBlank()) {
            return Result.success(LoginHelper.getOnlineUserCount(loginType));
        }
        return Result.success(LoginHelper.getOnlineUserCount());
    }

    /**
     * 增量游标分页检索在线会话（支持 loginType、keyword，返回 OnlineUser 含 tenantId / loginType / loginIp）
     */
    @GetMapping("/page")
    public Result<PageResult<OnlineUser>> searchOnlineUserPage(
            @RequestParam(value = "loginType", required = false) String loginType,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        if (loginType != null && !loginType.isBlank()) {
            return Result.success(LoginHelper.searchOnlineUserPage(loginType, keyword, page, size));
        }
        return Result.success(LoginHelper.searchOnlineUserPage(keyword, page, size));
    }

    @PostMapping("/kickout-token")
    public Result<Void> kickoutByToken(@NotBlank @RequestParam("tokenValue") String tokenValue) {
        LoginHelper.kickoutByToken(tokenValue);
        return Result.success();
    }

    @PostMapping("/kickout-device")
    public Result<Void> kickoutDevice(@NotBlank @RequestParam("userId") String userId,
                                      @NotBlank @RequestParam("device") String device) {
        LoginHelper.kickoutDevice(userId, device);
        return Result.success();
    }

    @PostMapping("/replaced")
    public Result<Void> replaced(@NotBlank @RequestParam("userId") String userId,
                                 @RequestParam(value = "device", defaultValue = "PC") String device) {
        LoginHelper.replaced(userId, device);
        return Result.success();
    }

    @PostMapping("/disable")
    public Result<String> disableUser(@NotBlank @RequestParam("userId") String userId,
                                      @RequestParam(value = "durationSeconds", defaultValue = "300") long durationSeconds) {
        LoginHelper.disable(userId, Duration.ofSeconds(durationSeconds));
        return Result.success("账号已封禁，时长: " + durationSeconds + " 秒");
    }

    @GetMapping("/restricted-action")
    @SaCheckDisable
    public Result<String> checkRestrictedAction() {
        return Result.success("受限操作调用成功");
    }
}
```

### 8.3 预期输出示例

```json
// GET /sample/satoken/online/page?loginType=login&keyword=admin&page=1&size=10
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "records": [
      {
        "userId": "10086",
        "userAccount": "admin",
        "userName": "admin",
        "tenantId": "tenant_sz",
        "loginType": "login",
        "tokenValue": "5a9f2d8e-7c41-4c72-9b1f-3d841a8c0e11",
        "loginDevice": "PC",
        "loginIp": "127.0.0.1",
        "loginTime": 1762992000000,
        "tokenTimeout": 86385
      }
    ],
    "total": 1,
    "size": 10,
    "current": 1,
    "pages": 1
  },
  "success": true
}

// 被 kickout 强退后原 Token 访问业务接口
{
  "code": 401,
  "msg": "当前账号已被踢下线",
  "data": null,
  "success": false
}
```

---

## 9. 声明式双轨白名单与动态 URL 路径权限管道

### 场景痛点
1. **静态白名单维护割裂与漏保护**：手工在配置文件硬编码放行路径容易在接口改名后遗漏；而整个 Controller 类公开时，偶发的 VIP 或高敏接口缺少快捷“反向收敛”能力；
2. **动态 URL 权限修改需要发版**：运营配置菜单权限映射后，硬编码的 `@SaCheckPermission` 无法运行时热刷新，且多账号体系下的同路径接口需要独立 `loginType` 路由隔离。

### 9.1 专属 YAML 配置片段

```yaml
ateng:
  boot:
    satoken:
      white-list:                 # 全局基础白名单（自动与 interceptor.excludes 及 @Anonymous/SPI 合并去重）
        - "/doc.html"
        - "/v3/api-docs/**"
      interceptor:
        enabled: true             # 是否挂载双轨拦截器 AtengSaTokenInterceptor (默认: true)
        check-login: false        # 严格模式：开启后除汇聚白名单外所有路由均强制 checkLogin() (默认: false)
        includes:
          - "/**"
        excludes:
          - "/sample/satoken/auth/login"
```

### 9.2 1:1 实战代码（源自 `PublicPortalController.java`、`SampleWhitelistProvider.java` 与 `SampleDynamicPathPermissionProvider.java`）

```java
// 1. 类级 @Anonymous 声明公开，局部高敏接口使用 @Anonymous(false) 反向收敛保护
@Slf4j
@RestController
@RequestMapping("/sample/satoken/portal")
@Anonymous
@RequiredArgsConstructor
public class PublicPortalController {

    private final SampleDynamicPathPermissionProvider dynamicPathPermissionProvider;

    @GetMapping("/announcement")
    public Result<String> getAnnouncement() {
        return Result.success("【系统公告】双十一全场云原生基础设施 Starter 限时特惠开启！");
    }

    @GetMapping("/products/{productId}")
    public Result<Map<String, Object>> getProductDetail(@PathVariable("productId") String productId) {
        // AnonymousUrlProcessor 启动时自动将 {productId} 转译为 /sample/satoken/portal/products/* 并入白名单
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("productId", productId);
        product.put("title", "Spring Boot 3 企业级微服务开发实战");
        product.put("price", new BigDecimal("99.00"));
        product.put("stock", 500);
        return Result.success(product);
    }

    @GetMapping("/products/{productId}/vip-price")
    @Anonymous(false)
    @SaCheckLogin
    public Result<Map<String, Object>> getVipPrice(@PathVariable("productId") String productId) {
        Map<String, Object> vipPrice = new LinkedHashMap<>();
        vipPrice.put("productId", productId);
        vipPrice.put("originalPrice", new BigDecimal("99.00"));
        vipPrice.put("vipPrice", new BigDecimal("69.00"));
        vipPrice.put("vipDiscount", "70%");
        return Result.success(vipPrice);
    }

    @GetMapping("/categories")
    @Anonymous(false) // 由 SampleWhitelistProvider SPI 动态放行 /sample/satoken/portal/categories/**
    public Result<List<String>> getCategories() {
        return Result.success(List.of("云计算", "中间件", "数据库", "开发框架"));
    }

    @GetMapping("/reports/daily")
    @Anonymous(false) // 拦截器动态匹配管道强制校验 portal:report:view
    public Result<String> getDailyReport() {
        return Result.success("每日运营分析报表数据汇总");
    }

    @PostMapping("/reports/daily")
    @Anonymous(false) // 拦截器动态匹配管道强制校验 portal:report:export
    public Result<String> exportDailyReport() {
        return Result.success("每日运营分析报表导出任务已提交");
    }

    @DeleteMapping("/reports/daily")
    @Anonymous(false) // 拦截器动态匹配管道强制校验 SaMode.AND: portal:report:delete + portal:report:audit
    public Result<String> deleteDailyReport() {
        return Result.success("每日运营历史归档报表已彻底销毁");
    }

    @GetMapping("/reports/unprotected")
    @Anonymous(false)
    public Result<String> getUnprotectedReport() {
        return Result.success("无约束报表数据公开汇总");
    }

    @GetMapping("/rules")
    public Result<List<PathPermissionRule>> getRules() {
        return Result.success(dynamicPathPermissionProvider.getPathPermissionRules());
    }

    @PostMapping("/rules/refresh")
    public Result<Void> refreshRules() {
        dynamicPathPermissionProvider.refresh();
        return Result.success();
    }
}

// 2. 动态白名单扩展 SPI（SampleWhitelistProvider.java）
@Component
public class SampleWhitelistProvider implements SaTokenWhitelistProvider {
    @Override
    public Set<String> getWhitelist() {
        return Set.of("/sample/satoken/portal/categories/**");
    }
}

// 3. 数据库驱动动态路径权限 SPI（SampleDynamicPathPermissionProvider.java）
@Component
public class SampleDynamicPathPermissionProvider implements DynamicPathPermissionProvider {

    private final List<PathPermissionRule> rules = new CopyOnWriteArrayList<>();

    public SampleDynamicPathPermissionProvider() {
        initDefaultRules();
    }

    private void initDefaultRules() {
        rules.clear();
        rules.add(PathPermissionRule.of("/sample/satoken/portal/reports/daily", "GET", "portal:report:view"));
        rules.add(PathPermissionRule.of("/sample/satoken/portal/reports/daily", "POST", "portal:report:export"));
        rules.add(PathPermissionRule.of("/sample/satoken/portal/reports/daily", "DELETE", SaMode.AND, "portal:report:delete", "portal:report:audit"));
    }

    @Override
    public List<PathPermissionRule> getPathPermissionRules() {
        return Collections.unmodifiableList(rules);
    }

    public synchronized void refresh() {
        initDefaultRules();
    }

    public synchronized void addRule(PathPermissionRule rule) {
        if (rule != null) {
            rules.add(rule);
        }
    }

    public synchronized void clearRules() {
        rules.clear();
    }
}
```

### 9.3 预期输出示例

```json
// GET /sample/satoken/portal/rules
{
  "code": 200,
  "msg": "操作成功",
  "data": [
    {
      "pathPattern": "/sample/satoken/portal/reports/daily",
      "httpMethod": "GET",
      "permissions": ["portal:report:view"],
      "mode": "OR"
    },
    {
      "pathPattern": "/sample/satoken/portal/reports/daily",
      "httpMethod": "POST",
      "permissions": ["portal:report:export"],
      "mode": "OR"
    },
    {
      "pathPattern": "/sample/satoken/portal/reports/daily",
      "httpMethod": "DELETE",
      "permissions": ["portal:report:delete", "portal:report:audit"],
      "mode": "AND"
    }
  ],
  "success": true
}

// 无 portal:report:view 权限访问 GET /sample/satoken/portal/reports/daily
{
  "code": 403,
  "msg": "无权访问：缺少所需权限码 [portal:report:view]",
  "data": null,
  "success": false
}
```

> `PathPermissionRule` 同时提供绑定 `loginType` 的工厂重载 `PathPermissionRule.of(pathPattern, httpMethod, loginType, mode, permissions)`，可在多账号系统下精确路由到 `admin` 或 `member` 体系执行权限断言。

---

## 10. 微服务内部零信任通信与客户端自动透传 (Same-Token)

### 场景痛点
内部微服务之间直接暴露的清算或管理接口若缺乏调用来源证明，绕过网关直连端口将引发重大内网安全漏洞；而要求每次使用 `RestClient` / `RestTemplate` 时人工从 `SaSameUtil` 读取并拼接请求头极易遗漏。

### 10.1 专属 YAML 配置片段

```yaml
ateng:
  boot:
    satoken:
      same-token:
        enabled: true             # 是否启用内部 Same-Token 校验 (默认: true)
        header-name: SA-SAME-TOKEN # 请求头名称 (默认: SA-SAME-TOKEN)
        token-value: "${SAME_TOKEN_SECRET:}" # 可选固定密钥（留空时采用 Sa-Token 动态轮转凭据；开启后使用 MessageDigest.isEqual 常数时间比对）
        auto-inject-client: true  # 自动为 RestClient.Builder 与 RestTemplate 注入 SameTokenClientHttpRequestInterceptor (默认: true)
```

### 10.2 1:1 实战代码（源自 `InternalClusterController.java`）

```java
@Slf4j
@RestController
@RequiredArgsConstructor
public class InternalClusterController {

    private final RestClient.Builder restClientBuilder;

    /**
     * 服务端受 @CheckSameToken 零信任保护的内部清算接口
     */
    @GetMapping("/sample/satoken/internal/clearing/voucher")
    @Anonymous
    @CheckSameToken
    public Result<String> getClearingVoucher() {
        return Result.success("清算凭证数据 [CLR-20261108-9988]");
    }

    /**
     * 客户端通过受管 RestClient.Builder 发起跨服务调用（自动透明携带 SA-SAME-TOKEN 请求头）
     */
    @PostMapping("/sample/satoken/internal/call-clearing")
    @Anonymous
    public Result<String> callClearing(@RequestParam(value = "targetUrl", required = false) String targetUrl) {
        String url = (targetUrl != null && !targetUrl.isBlank())
                ? targetUrl
                : "http://localhost:8080/sample/satoken/internal/clearing/voucher";
        try {
            String response = restClientBuilder.build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
            return Result.success("内部清算远程调用成功，下游返回: " + response);
        } catch (Exception e) {
            log.error("内部清算远程调用异常: ", e);
            return Result.fail("内部调用失败: " + e.getMessage());
        }
    }
}
```

### 10.3 预期输出与生态正交边界

```json
// 未携带有效 SA-SAME-TOKEN 请求头直连 /sample/satoken/internal/clearing/voucher
{
  "code": 403,
  "msg": "微服务内部鉴权凭据无效或已失效",
  "data": null,
  "success": false
}
```

> 微服务生态协同边界（参见 [ADR-0059](../docs/adr/0059-satoken-hierarchical-cache-multi-account-and-zero-trust-closed-loop-architecture.md)）：跨服务 OpenFeign RPC 由 `ateng-cloud-feign` 自动透传四维上下文与 Same-Token，Dubbo RPC 由 `ateng-cloud-dubbo` 透传验签，WebFlux 响应式网关准入由 `ateng-cloud-gateway` 承接；`ateng-boot-satoken` 专注单体与微服务服务端零信任防线及 Spring 原生 HTTP 客户端出站透传。

---

## 11. 身份模拟双维审计与 Spring 原生安全事件总线

### 场景痛点
客服代客排障或系统自动补偿订单时，既需要持久/瞬态以目标用户上下文运行（驱动 MyBatis-Plus 自动填入被模拟人 `createBy`），又必须不可篡改地记录最初真实登录操作人以便风控追溯；同时登录、注销、踢人、封禁与二级认证事件需零侵入地接入 Spring 事件总线。

### 11.1 专属 YAML 配置片段

```yaml
ateng:
  boot:
    satoken:
      enabled: true               # 自动装配 SaTokenEventPublisher 桥接 8 类 Spring ApplicationEvent
```

### 11.2 1:1 实战代码（源自 `ImpersonateAuditController.java` 与 `SampleSecurityEventListener.java`）

```java
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/satoken/impersonate")
@RequiredArgsConstructor
public class ImpersonateAuditController {

    private final SaTokenSampleService sampleService;
    private final SampleSecurityEventListener securityEventListener;

    /**
     * 1. 会话级持久身份模拟（switchTo / endSwitch）与双维状态查询
     */
    @PostMapping("/switch")
    public Result<String> switchTo(@NotBlank @RequestParam("targetUserId") String targetUserId) {
        LoginHelper.switchTo(targetUserId);
        return Result.success("会话已切换至目标用户: " + targetUserId);
    }

    @GetMapping("/status")
    public Result<ImpersonateStatusVO> getImpersonateStatus() {
        return Result.success(ImpersonateStatusVO.builder()
                .isRunAs(LoginHelper.isRunAs())
                .currentUserId(LoginHelper.getUserId())         // 当前生效目标用户
                .realUserId(LoginHelper.getRealUserId())       // 始终锁定真实登录人
                .originalUserId(LoginHelper.getOriginalUserId())
                .build());
    }

    @PostMapping("/end-switch")
    public Result<String> endSwitch() {
        LoginHelper.endSwitch();
        return Result.success("已结束会话模拟，恢复原始身份");
    }

    /**
     * 2. 代码块级瞬态身份模拟（runAs，栈式保护并在 finally 中自动还原）
     */
    @PostMapping("/compensate-order")
    public Result<Long> compensateOrder(@NotBlank @RequestParam("targetUserId") String targetUserId,
                                        @NotBlank @RequestParam("orderNo") String orderNo,
                                        @NotNull @DecimalMin("0.01") @RequestParam("amount") BigDecimal amount) {
        Long orderId = LoginHelper.runAs(targetUserId, () -> sampleService.createOrder(orderNo, amount));
        return Result.success(orderId);
    }

    @GetMapping("/events")
    public Result<List<SecurityEventVO>> getSecurityEvents() {
        return Result.success(securityEventListener.getAllEventsList());
    }
}
```

监听全生命周期事件（源自 `SampleSecurityEventListener.java`）：

```java
@Slf4j
@Component
public class SampleSecurityEventListener {

    private final List<SaLoginEvent> loginEvents = new CopyOnWriteArrayList<>();
    private final List<SaKickoutEvent> kickoutEvents = new CopyOnWriteArrayList<>();
    private final List<SecurityEventVO> allEvents = new CopyOnWriteArrayList<>();

    @EventListener
    public void onLogin(SaLoginEvent event) {
        log.info("【安全事件监听】收到用户登录事件: userId={}, token={}", event.getLoginId(), event.getTokenValue());
        loginEvents.add(event);
        allEvents.add(SecurityEventVO.builder()
                .eventType("LOGIN")
                .loginType(event.getLoginType())
                .loginId(String.valueOf(event.getLoginId()))
                .tokenValue(event.getTokenValue())
                .details("用户登录成功")
                .timestamp(LocalDateTime.now())
                .build());
    }

    @EventListener
    public void onLogout(SaLogoutEvent event) {
        log.info("【安全事件监听】收到用户注销事件: userId={}, token={}", event.getLoginId(), event.getTokenValue());
        allEvents.add(SecurityEventVO.builder()
                .eventType("LOGOUT")
                .loginType(event.getLoginType())
                .loginId(String.valueOf(event.getLoginId()))
                .tokenValue(event.getTokenValue())
                .details("用户主动注销")
                .timestamp(LocalDateTime.now())
                .build());
    }

    @EventListener
    public void onKickout(SaKickoutEvent event) {
        log.info("【安全事件监听】收到用户被踢下线事件: userId={}, token={}", event.getLoginId(), event.getTokenValue());
        kickoutEvents.add(event);
        allEvents.add(SecurityEventVO.builder()
                .eventType("KICKOUT")
                .loginType(event.getLoginType())
                .loginId(String.valueOf(event.getLoginId()))
                .tokenValue(event.getTokenValue())
                .details("用户被强制踢下线")
                .timestamp(LocalDateTime.now())
                .build());
    }

    @EventListener
    public void onReplaced(SaReplacedEvent event) {
        log.info("【安全事件监听】收到用户被顶替下线事件: userId={}, token={}", event.getLoginId(), event.getTokenValue());
        allEvents.add(SecurityEventVO.builder()
                .eventType("REPLACED")
                .loginType(event.getLoginType())
                .loginId(String.valueOf(event.getLoginId()))
                .tokenValue(event.getTokenValue())
                .details("用户同端被新登录顶替")
                .timestamp(LocalDateTime.now())
                .build());
    }

    @EventListener
    public void onDisable(SaDisableEvent event) {
        log.info("【安全事件监听】收到账号封禁事件: userId={}, service={}, time={}s",
                event.getLoginId(), event.getService(), event.getDisableTime());
        allEvents.add(SecurityEventVO.builder()
                .eventType("DISABLE")
                .loginType(event.getLoginType())
                .loginId(String.valueOf(event.getLoginId()))
                .details("账号被封禁: service=" + event.getService() + ", duration=" + event.getDisableTime() + "s")
                .timestamp(LocalDateTime.now())
                .build());
    }

    @EventListener
    public void onUntieDisable(SaUntieDisableEvent event) {
        log.info("【安全事件监听】收到账号解封事件: userId={}, service={}", event.getLoginId(), event.getService());
        allEvents.add(SecurityEventVO.builder()
                .eventType("UNTIE_DISABLE")
                .loginType(event.getLoginType())
                .loginId(String.valueOf(event.getLoginId()))
                .details("账号解除封禁: service=" + event.getService())
                .timestamp(LocalDateTime.now())
                .build());
    }

    @EventListener
    public void onOpenSafe(SaOpenSafeEvent event) {
        log.info("【安全事件监听】收到开启二级安全认证事件: service={}, time={}s", event.getService(), event.getSafeTime());
        allEvents.add(SecurityEventVO.builder()
                .eventType("OPEN_SAFE")
                .loginType(event.getLoginType())
                .details("开启二级安全认证: service=" + event.getService() + ", time=" + event.getSafeTime() + "s")
                .timestamp(LocalDateTime.now())
                .build());
    }

    @EventListener
    public void onCloseSafe(SaCloseSafeEvent event) {
        log.info("【安全事件监听】收到关闭二级安全认证事件: service={}", event.getService());
        allEvents.add(SecurityEventVO.builder()
                .eventType("CLOSE_SAFE")
                .loginType(event.getLoginType())
                .details("关闭二级安全认证: service=" + event.getService())
                .timestamp(LocalDateTime.now())
                .build());
    }
}
```

### 11.3 预期输出示例

```json
// GET /sample/satoken/impersonate/status（客服 "admin-kefu" 切换至 "10086" 后）
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "isRunAs": true,
    "currentUserId": "10086",
    "realUserId": "admin-kefu",
    "originalUserId": "admin-kefu"
  },
  "success": true
}
```

---

## 12. OpenAPI / Knife4j 鉴权弹窗无侵入自动化

### 场景痛点
前后端联调或通过 Swagger / Knife4j 文档调试受保护接口时，手工在每个请求单独填写真实请求头极其繁琐。当类路径引入 `springdoc-openapi-starter-webmvc-ui` 时，`SaTokenOpenApiCustomizer` 自动在 OpenAPI Schema 中注册全局 Header 鉴权定义。

### 12.1 专属 YAML 配置片段

```yaml
ateng:
  boot:
    satoken:
      openapi:
        enabled: true             # 是否自动注入 Knife4j / OpenAPI 全局鉴权弹窗 (默认: true)
        token-name: satoken       # 鉴权 Header 名称（未配置时自动回退 sa-token.token-name，兜底 satoken）
```

### 12.2 定制扩展与调用效果

若需多体系文档联动或自定义定制器，可在应用配置中直接观察 `/v3/api-docs` 装配结果或在 `SaTokenOpenApiCustomizerTest` 中验证：

```java
// 自动化装配条件（源自 AtengSaTokenAutoConfiguration.java）
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(GlobalOpenApiCustomizer.class)
public static class OpenApiConfiguration {

    @Bean
    @ConditionalOnMissingBean(SaTokenOpenApiCustomizer.class)
    @ConditionalOnProperty(prefix = "ateng.boot.satoken.openapi", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SaTokenOpenApiCustomizer saTokenOpenApiCustomizer(SaTokenProperties properties) {
        return new SaTokenOpenApiCustomizer(properties);
    }
}
```

### 12.3 预期 OpenAPI SecurityScheme 片段

```json
{
  "components": {
    "securitySchemes": {
      "satoken": {
        "type": "apiKey",
        "name": "satoken",
        "in": "header"
      }
    }
  },
  "security": [
    {
      "satoken": []
    }
  ]
}
```

---

## 13. 全量配置项与默认值参考 (`SaTokenProperties`)

| YAML 全路径 (`ateng.boot.satoken.*`) | Java 类型 | 默认值 | 说明 |
| :--- | :--- | :--- | :--- |
| `enabled` | `boolean` | `true` | 模块增强与自动装配总开关 |
| `primary-login-type` | `String` | `"login"` | 多账号共存时主上下文映射体系标识 |
| `white-list` | `List<String>` | `["/doc.html", "/webjars/**", "/swagger-resources/**", "/v3/api-docs/**", "/favicon.ico", "/actuator/**"]` | 全局静态路由白名单（与 `interceptor.excludes` 合并去重） |
| `token.timeout` | `Duration` | `24h` | Token 绝对有效期（双向桥接至 Sa-Token） |
| `token.active-timeout` | `Duration` | `30m` | 活跃空闲超时时长（<= 0 关闭） |
| `token.is-concurrent` | `boolean` | `true` | 是否允许同账号并发多端登录 |
| `token.is-share` | `boolean` | `true` | 同端多次登录是否共用 Token |
| `token.max-login-count` | `int` | `12` | 最大并发会话数上限 |
| `token.token-style` | `String` | `"uuid"` | Token 生成算法风格 |
| `interceptor.enabled` | `boolean` | `true` | 是否挂载双轨拦截器与 `@Anonymous` 处理器 |
| `interceptor.check-login` | `boolean` | `false` | 严格全局登录拦截开关 |
| `interceptor.includes` | `List<String>` | `["/**"]` | 拦截器匹配路径模式 |
| `interceptor.excludes` | `List<String>` | `["/doc.html", "/webjars/**", "/swagger-resources/**", "/v3/api-docs/**", "/favicon.ico", "/actuator/**", "/error", "/*.html", "/*.css", "/*.js"]` | 拦截器排除路径列表（注意：YAML 中显式覆盖该 `List` 时将替换默认集合，建议完整保留或通过 `@Anonymous`/`SaTokenWhitelistProvider` 增量追加） |
| `same-token.enabled` | `boolean` | `true` | 微服务内部 Same-Token 校验开关 |
| `same-token.header-name` | `String` | `"SA-SAME-TOKEN"` | Same-Token 请求头名称 |
| `same-token.token-value` | `String` | `null` | 可选固定密钥（留空使用动态轮转凭据） |
| `same-token.auto-inject-client` | `boolean` | `true` | 是否为受管 HTTP 客户端自动注入拦截器 |
| `permission-cache.enabled` | `boolean` | `true` | 是否开启权限与角色缓存 |
| `permission-cache.timeout` | `Duration` | `5m` (`300s`) | 权限缓存基础有效期（同时向后兼容 `timeout-seconds` 别名） |
| `permission-cache.empty-ttl` | `Duration` | `60s` | 空集合 `__EMPTY__` 防穿透哨兵有效期（同时向后兼容 `empty-ttl-seconds` 别名） |
| `cluster-sync.enabled` | `boolean` | `true` | 是否开启 Redis Pub/Sub 集群同步广播 |
| `cluster-sync.channel` | `String` | `"ateng:satoken:cluster-sync"` | Redis Pub/Sub 广播频道名称 |
| `login-defense.enabled` | `boolean` | `false` | 是否开启双轨防暴力破解策略 |
| `login-defense.max-retry-count` | `int` | `5` | 账号最大连续失败次数 |
| `login-defense.lock-time` | `Duration` | `15m` | 账号锁定持续时长 |
| `login-defense.window-time` | `Duration` | `15m` | 账号失败计数统计时间窗口 |
| `login-defense.ip-max-retry-count` | `int` | `30` | 客户端 IP 最大连续失败次数 |
| `login-defense.ip-lock-time` | `Duration` | `30m` | 客户端 IP 熔断锁定持续时长 |
| `login-defense.ip-window-time` | `Duration` | `15m` | 客户端 IP 失败计数统计时间窗口 |
| `login-defense.ip-white-list` | `List<String>` | `["127.0.0.1", "0:0:0:0:0:0:0:1", "::1"]` | IP 防爆破豁免白名单（支持 CIDR） |
| `data-owner.enabled` | `boolean` | `true` | `@CheckDataOwner` 水平越权防御切面开关 |
| `openapi.enabled` | `boolean` | `true` | OpenAPI / Knife4j 鉴权注入开关 |
| `openapi.token-name` | `String` | `null` | Swagger Header 名称（默认回退 Sa-Token 配置） |

<details>
<summary>点击展开完整生产参考 application.yml 配置模板</summary>

```yaml
sa-token:
  token-name: satoken
  is-read-header: true
  is-read-cookie: false
  is-log: false
  same-token-timeout: 86400

ateng:
  boot:
    satoken:
      enabled: true
      primary-login-type: login
      white-list:
        - "/doc.html"
        - "/webjars/**"
        - "/swagger-resources/**"
        - "/v3/api-docs/**"
        - "/favicon.ico"
        - "/actuator/**"
      token:
        timeout: 24h
        active-timeout: 30m
        is-concurrent: true
        is-share: true
        max-login-count: 12
        token-style: uuid
      interceptor:
        enabled: true
        check-login: false
        includes:
          - "/**"
        # 注意：YAML 配置 List 会整体覆盖默认排除列表，生产中建议保留以下 10 项基础放行后再追加业务路径
        excludes:
          - "/doc.html"
          - "/webjars/**"
          - "/swagger-resources/**"
          - "/v3/api-docs/**"
          - "/favicon.ico"
          - "/actuator/**"
          - "/error"
          - "/*.html"
          - "/*.css"
          - "/*.js"
          - "/sample/satoken/auth/login"
      same-token:
        enabled: true
        header-name: SA-SAME-TOKEN
        token-value: "${SAME_TOKEN_SECRET:}"
        auto-inject-client: true
      permission-cache:
        enabled: true
        timeout: 5m
        empty-ttl: 60s
      cluster-sync:
        enabled: true
        channel: "ateng:satoken:cluster-sync"
      login-defense:
        enabled: true
        max-retry-count: 5
        lock-time: 15m
        window-time: 15m
        ip-max-retry-count: 30
        ip-lock-time: 30m
        ip-window-time: 15m
        ip-white-list:
          - "127.0.0.1"
          - "0:0:0:0:0:0:0:1"
          - "::1"
      data-owner:
        enabled: true
      openapi:
        enabled: true
        token-name: satoken
```

</details>

---

## 14. 生产扩展与 SPI 接管指南 (Customization & Extensibility)

| 扩展点 SPI / Bean | 默认实现 | 典型业务接管场景 |
| :--- | :--- | :--- |
| `UserRolePermissionLoader` | `DefaultUserRolePermissionLoader` | 从数据库 RBAC 表实时加载角色、权限码与用户直属权限；重写 `(id, loginType)` 重载实现多账号体系物理隔离查询 |
| `DataOwnerProvider<T>` | `DefaultDataOwnerProvider` | 根据业务主键（如订单 ID、文件 ID）反查真实归属人 `userId`，支持接收 `loginType` 区分多端用户主键空间 |
| `DynamicPathPermissionProvider` | 无（未注册自动跳过） | 从数据库菜单/接口表动态加载 `List<PathPermissionRule>`，支持 `AntPath`、`HTTP Method` 与可选 `loginType` |
| `SaTokenWhitelistProvider` | 无 | 由业务模块或第三方 Starter 动态向 `AtengSaTokenInterceptor` 汇聚公开路由白名单 |
| `SaTokenDao` | `SaTokenRedisDao`（Redis 可用时） | 替换或定制自定义会话持久化引擎（注入 `@Primary` 同类型 Bean 即可平滑覆盖） |
| `LoginHelper.registerStpLogic` | `LoginHelper` 懒加载默认构造 | 预先绑定拥有专属前缀或生命周期策略的自定义 `StpLogic` 实例 |

### 接入自定义数据库权限加载器示例（多账号体系隔离版）

```java
@Component
public class DatabaseUserRolePermissionLoader implements UserRolePermissionLoader {

    @Override
    public List<String> loadRolesByUserId(String userId) {
        return Collections.emptyList();
    }

    @Override
    public List<String> loadRolesByUserId(String userId, String loginType) {
        // 按账号体系（admin / member）分别查询各自角色表，彻底隔离缓存与鉴权空间
        return "admin".equals(loginType) ? queryAdminRoles(userId) : queryMemberRoles(userId);
    }

    @Override
    public List<String> loadPermissionsByRole(String roleCode) {
        return Collections.emptyList();
    }

    @Override
    public List<String> loadPermissionsByRole(String roleCode, String loginType) {
        return "admin".equals(loginType) ? queryAdminRolePerms(roleCode) : queryMemberRolePerms(roleCode);
    }
}
```

---

## 15. 生产并发边界、虚拟线程安全与异常转译

1. **JDK 21+ 虚拟线程零 Pinning 保证**：`LoginHelper`、`PermissionCacheService`、`AtengSaTokenInterceptor` 与 `AnonymousUrlProcessor` 中全部懒加载与互斥临界区均已切换为 `ReentrantLock`，并遵循 `isHeldByCurrentThread()` 守卫下的 `finally` 释放范式，严防载体线程挂起；
2. **Redis 游标增量扫描与分页硬封顶**：`SaTokenRedisDao.searchData` 全量使用 `ScanOptions.count(1000)` 游标分批迭代替代 `KEYS *`；`LoginHelper` 在线会话关键字检索设置上限 `MAX_KEYWORD_SCAN_LIMIT = 500`、单页最大容量 `100`；
3. **内存降级有界防御防 OOM**：无 Redis 时，`InMemoryPermissionCacheAdapter` 严格维持 `10,000` 条 LRU 上限，`LoginFailureLockService` 每个本地计数/封禁容器均限容 `10,000` 并惰性淘汰过期条目；
4. **防穿透与防雪崩双保险**：`CachedStpInterface` 对空权限/空角色写入 `__EMPTY__` 短效哨兵（`empty-ttl: 60s`），对非空缓存自动附加 `+10% ~ +20%` 动态随机 Jitter；
5. **Sa-Token 防火墙统管响应**：启动期自动接管 `SaFirewallStrategy.instance.checkFailHandle`，命中非法字符或路径穿越探针时在 HTTP 200 下返回统一 JSON `Result.fail(400, "非法请求拦截: ...")`；
6. **全局异常映射一览 (`SaTokenExceptionHandler` 与 `BusinessException` 协同转译)**：

| 触发异常 / 拦截场景 | 处理处理器 | HTTP 状态码 | `Result.code` | 提示语契约示例 |
| :--- | :--- | :--- | :--- | :--- |
| `NotLoginException`（未提供/无效/过期/被顶替/被踢/冻结/无前缀） | `SaTokenExceptionHandler` | `200` | `401` | `"未提供访问凭据 (Token)"` / `"当前账号已在其他设备登录，您已被顶下线"` / `"当前账号已被踢下线"` 等 |
| `NotRoleException` / `NotPermissionException` | `SaTokenExceptionHandler` | `200` | `403` | `"无权访问：缺少所需角色 [admin]"` / `"无权访问：缺少所需权限码 [order:delete]"` |
| `NotSafeException` | `SaTokenExceptionHandler` | `200` | `403` | `"无权访问：当前操作需要二级安全认证"` |
| `DisableServiceException` | `SaTokenExceptionHandler` | `200` | `403` | `"当前账号已被封禁至 " + e.getDisableTime()` |
| `SameTokenInvalidException` | `SaTokenExceptionHandler` | `200` | `403` | `"微服务内部鉴权凭据无效或已失效"` |
| 其它 `SaTokenException` / 防火墙拦截 | `SaTokenExceptionHandler` / `SaFirewallStrategy` | `200` | `400` | 具体安全或防火墙拦截提示（防火墙返回 `Result.fail(400, ...)`） |
| `@CheckDataOwner` / `LoginHelper.checkSelf*` / `LoginFailureLockService.checkIpLocked` / 临时令牌反序列化失败 | `ateng-boot-web` `GlobalExceptionHandler`（捕获 `BusinessException`） | `200` | `403` / `400` | `"无权访问该资源"` / `"客户端 IP 连续登录失败次数超限，已被临时锁定"` / 具体临时令牌校验失败提示 |

---

## 16. 架构决策与设计参考

- [Sa-Token 领域模型术语表](./CONTEXT.md)
- [Sample 体验工程与 80 个端点一键体验卡](../ateng-boot-samples/ateng-boot-sample-satoken/README.md)
- [ADR-0024: 企业级 Sa-Token 安全鉴权与分布式会话治理架构](../docs/adr/0024-enterprise-satoken-authentication-and-security-architecture.md)
- [ADR-0059: 企业级 Sa-Token 级联两级缓存、多账号体系与微服务零信任全闭环架构决策](../docs/adr/0059-satoken-hierarchical-cache-multi-account-and-zero-trust-closed-loop-architecture.md)
- [ADR-0002: 显式统一响应契约](../docs/adr/0002-explicit-result-contract-no-response-body-advice.md)
- [ADR-0012: 生命周期分离的数据安全模型与内存真明文原则](../docs/adr/0012-lifecycle-separated-data-security.md)
- [ADR-0022: 双核驱动 Redis 基础设施与分布式协同架构](../docs/adr/0022-dual-core-redis-and-distributed-coordination-architecture.md)
- [ADR-0029: 虚拟线程适配与异步任务四维上下文透明穿透架构](../docs/adr/0029-virtual-threads-and-adaptive-async-runtime.md)
