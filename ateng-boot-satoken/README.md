# ateng-boot-satoken 安全鉴权与分布式会话治理套件

> 面向生产就绪的 Sa-Token 企业级安全基础设施与分布式会话治理底座，提供轻量无侵入鉴权、强类型 `LoginUser` / `LoginHelper` 载荷模型、双轨拦截防御体系、Core / MyBatis-Plus 审计全链路贯通、条带锁两级权限缓存与动态热刷新、多账号独立体系会话物理隔离、客户端 IP 自动嗅探双轨防爆破熔断、微服务内部零信任 Same-Token 出站自动透传、在线会话安全游标监控与精准下线治理、数据库驱动动态 URL 路径权限匹配 SPI、Spring 原生安全事件总线及 OpenAPI / Knife4j 鉴权弹窗无侵入自动化集成。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **会话登录** | 强类型用户登录 | `LoginHelper` | `ateng.boot.satoken.enabled` | `login(LoginUser)` / `login(LoginUser, String)` / `login(LoginUser, SaLoginParameter)` / `login(LoginUser, String, String)` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **会话注销** | 会话安全注销 | `LoginHelper` | - | `logout()` / `logout(String)` / `logoutByTokenValue(String)` / `logoutByDevice(String)` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **设备治理** | 账号踢人与顶替下线 | `LoginHelper` | - | `kickout(Object loginId)` / `kickout(Object, String)` / `kickoutByToken(String)` / `kickoutByDevice(Object, String)` / `replaced(Object)` / `replaced(Object, String)` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **账号封禁** | 账号/服务封禁与解封 | `LoginHelper` | - | `disable(Object, Duration)` / `disable(Object, String, Duration)` / `untieDisable(Object)` / `untieDisable(Object, String)` / `isDisable` / `checkDisable` / `getDisableTime` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **二级认证** | 编程式二级安全认证 | `LoginHelper` | - | `openSafe(Duration)` / `openSafe(int)` / `openSafe(String, Duration)` / `isSafe()` / `isSafe(String)` / `checkSafe()` / `checkSafe(String)` / `closeSafe()` / `closeSafe(String)` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **状态查询** | 登录状态与令牌查询 | `LoginHelper` | - | `isLogin()` / `isLogin(String)` / `checkLogin()` / `checkLogin(String)` / `getTokenValue()` / `getTokenValue(String)` / `getTokenValueByLoginId(Object)` / `getTokenValueByLoginId(Object, String)` / `getTokenTimeout()` / `getSessionTimeout()` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **载荷存取** | 强类型实体存取 | `LoginHelper` | - | `setLoginUser(LoginUser)` / `setLoginUser(LoginUser, String)` / `getLoginUser()` / `getLoginUser(String)` / `getLoginUser(Class<T>)` / `getLoginUser(Class<T>, String)` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **身份感知** | 用户/租户身份提取 | `LoginHelper` | - | `getUserId()` / `getUserId(String)` / `getUserIdAsLong()` / `getUserIdAsLong(String)` / `getRealUserId()` / `getRealUserIdAsLong()` / `getUserAccount()` / `getUserName()` / `getTenantId()` / `getDeptId()` / `buildCompositeLoginId` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **多账号体系** | 独立账号体系与隔离登录 | `LoginHelper` | `ateng.boot.satoken.primary-login-type` | `getStpLogic(String loginType)` / `setPrimaryLoginType(String)` / `getPrimaryLoginType()` / `registerStpLogic(String, StpLogic)`，多体系 Token 请求头与 Session 物理隔离 | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **身份模拟** | 会话持久模拟与还原 | `LoginHelper` | - | `switchTo(String)` / `switchTo(Long)` / `endSwitch()` 会话级模拟，保留原始运维人链路 | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **身份模拟** | 瞬态代码块安全模拟 | `LoginHelper` | - | `runAs(String, Supplier<T>)` / `runAs(String, Runnable)` / `runAs(Long, ...)` 栈式保护与 `finally` 可靠清理 | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **双维审计** | 模拟感知与原始人追踪 | `LoginHelper`<br>`SaTokenCurrentUserProvider` | - | `isRunAs()` / `getOriginalUserId()`，自动贯通 Core/Web `@OperateLog` 切面双维操作审计 | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **越权防御** | 数据所有权防卫断言 | `@CheckDataOwner`<br>`DataOwnerProvider`<br>`LoginHelper` | `ateng.boot.satoken.data-owner.enabled` | `@CheckDataOwner(expr, provider, allowAdmin, message)` / `DataOwnerProvider.getOwnerId` / `checkSelf(String/Long)` / `isSelf(String/Long)` / `isSelfOrAdmin` / `checkBelongsToTenant` / `isBelongsToTenant` | `SaCheckDataOwnerAspectTest` / `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **公开访问** | 声明式公开与动态白名单 | `@Anonymous`<br>`SaTokenWhitelistProvider`<br>`AnonymousUrlProcessor` | `ateng.boot.satoken.white-list`<br>`ateng.boot.satoken.interceptor.excludes` | 类/方法级 `@Anonymous`，`SaTokenWhitelistProvider.getWhitelist()`，动态扫描转译 AntPath，多源白名单聚合放行 | `AnonymousUrlProcessorTest` / `SampleSaTokenIntegrationTest` |
| **权限断言** | 角色与特权判定 | `LoginHelper` | - | `getRoles()` / `getPermissions()` / `hasRole(String)` / `hasPermission(String)` / `isSuperAdmin()` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **用户载荷** | 工业级强类型载荷 | `LoginUser` | - | 标配 `@SaJsonType`、链式 setter 与 `extraData` 扩展字典 | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **路由拦截** | 双轨路由拦截与白名单 | `AtengSaTokenInterceptor`<br>`SaTokenWebMvcConfigurer` | `ateng.boot.satoken.interceptor.*`<br>`ateng.boot.satoken.white-list` | `isAnnotation(false)` 专精路径白名单防漏，`preHandle` 注入上下文，`afterCompletion` 强制清空 | `AtengSaTokenInterceptorTest` / `SampleSaTokenIntegrationTest` |
| **注解鉴权** | 细粒度声明式鉴权 | `@SaCheckLogin`<br>`@SaCheckRole`<br>`@SaCheckPermission`<br>`@SaCheckSafe`<br>`@SaCheckDisable` | - | AOP 切面统一承接 Controller 与 Service 边界方法鉴权，彻底消除双重校验损耗 | `SampleSaTokenIntegrationTest` |
| **内部零信任** | 微服务内部防伪装调用 | `@CheckSameToken`<br>`SaCheckSameTokenAspect` | `ateng.boot.satoken.same-token.enabled`<br>`ateng.boot.satoken.same-token.header-name`<br>`ateng.boot.satoken.same-token.token-value` | 校验内网微服务调用凭据，防网关绕过探测；支持配置开关与自定义请求头 | `SaCheckSameTokenAspectTest` / `SampleSaTokenIntegrationTest` |
| **出站自动透传** | 客户端出站拦截器自动装配 | `SameTokenClientHttpRequestInterceptor`<br>`RestClientCustomizer`<br>`RestTemplateCustomizer` | `ateng.boot.satoken.same-token.auto-inject-client` | 自动为 Spring 受管 RestClient/RestTemplate 注入拦截器，跨微服务发起请求时透明打标 `SA-SAME-TOKEN` | `SameTokenClientConfigurationTest` / `SampleSaTokenIntegrationTest` |
| **两级缓存** | 条带锁防击穿权限缓存 | `CachedStpInterface`<br>`PermissionCacheService` | `ateng.boot.satoken.permission-cache.*` | 基于 `TwoLevelCacheManager` 实现条带锁并发防击穿、Caffeine L1 纳秒微缓存、Redis L2 分布式缓存、空哨兵与动态 Jitter | `CachedStpInterfaceTest` / `PermissionCacheServiceTest` / `SampleSaTokenIntegrationTest` |
| **业务 SPI** | 角色与权限业务加载器 | `UserRolePermissionLoader` | - | `loadRolesByUserId(String)` / `loadPermissionsByRole(String)` / `loadDirectPermissionsByUserId(String)` | `CachedStpInterfaceTest` / `SampleSaTokenIntegrationTest` |
| **Core 贯通** | 当前操作人/租户 SPI 适配 | `SaTokenCurrentUserProvider`<br>`SaTokenCurrentTenantProvider` | - | 自动向 Core 贯通当前用户与租户，驱动 MyBatis-Plus 审计自动填充与多租户行级隔离 | `SampleSaTokenIntegrationTest` |
| **事件总线** | 原生安全事件广播 | `SaTokenEventPublisher` | - | 桥接广播 `SaLoginEvent`、`SaLogoutEvent`、`SaKickoutEvent`、`SaReplacedEvent`、`SaDisableEvent` 等 Spring 原生事件 | `SaTokenEventPublisherTest` / `SampleSaTokenIntegrationTest` |
| **OpenAPI 适配** | Knife4j 鉴权弹窗自动化 | `SaTokenOpenApiCustomizer` | `ateng.boot.satoken.openapi.enabled`<br>`ateng.boot.satoken.openapi.token-name` | 自动在 `/v3/api-docs` 与 Knife4j 界面右上角挂载 Authorize 鉴权弹窗 | `SaTokenOpenApiCustomizerTest` / `SampleSaTokenIntegrationTest` |
| **分布式存储** | 纯净 JSON 会话存储驱动 | `SaTokenRedisDao` | - | 基于 `StringRedisTemplate` 存储，杜绝 `@class` 反序列化白名单漏洞，无 Redis 自动平滑降级 | `SaTokenRedisDaoTest` / `SampleSaTokenIntegrationTest` |
| **集群同步** | 分布式集群即时同步与广播作废 | `SaTokenClusterBroadcaster`<br>`SaTokenClusterSyncListener` | `ateng.boot.satoken.cluster-sync.enabled`<br>`ateng.boot.satoken.cluster-sync.channel` | `broadcast(eventType, targetKey)` / `getInstanceId()` / `processRemoteMessage(msg)` / 自消费防卫丢弃与异地本地缓存精准失效 | `SaTokenClusterSyncTest` / `SampleSaTokenIntegrationTest` |
| **防暴力破解** | 双轨防暴力破解与 IP 嗅探 | `LoginFailureLockService` | `ateng.boot.satoken.login-defense.*` | `recordFailure(account, clientIp)` / `recordIpFailure(clientIp)` / `sniffClientIp()` / `resolveClientIp(clientIp)` / `isIpLocked(clientIp)` / `checkIpLocked(clientIp)` / `isIpWhiteListed(clientIp)` / `clearAllFailure(account, clientIp)`，支持 IPv4/IPv6/CIDR 白名单与账号+IP 双轨熔断 | `LoginFailureLockServiceTest` / `SampleSaTokenIntegrationTest` |
| **在线会话** | 在线会话监控与增量游标治理 | `LoginHelper`<br>`OnlineUser` | - | `getOnlineUsers(page, size, keyword, loginType)` / `getOnlineUserCount(loginType)`，采用增量游标 SCAN 检索与分页切片，防内存 OOM | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **动态权限** | 动态 URL 路径权限匹配 SPI | `DynamicPathPermissionProvider`<br>`PathPermissionRule` | `ateng.boot.satoken.interceptor.enabled` | `DynamicPathPermissionProvider.getPathPermissionRules()`，`AtengSaTokenInterceptor` 自动挂载管道，按 AntPath + HTTP Method 动态执行权限鉴权 | `AtengSaTokenInterceptorTest` / `SampleSaTokenIntegrationTest` |
| **异常转译** | 统一显式 Result 响应 | `SaTokenExceptionHandler` | - | 将未登录 (401)、权限不足 (403)、服务封禁 (403)、Same-Token 无效 (403) 等转译为 HTTP 200 + `Result<Void>` 显式契约 | `SampleSaTokenIntegrationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-satoken` 深度融合 Sa-Token `1.44.0` 核心能力，恪守 `ateng-boot` 规范：
- 严格遵循显式强类型契约（[ADR-0002](../docs/adr/0002-explicit-result-contract-no-response-body-advice.md)），将全量鉴权异常统一转译为 HTTP 200 + `Result<T>` 格式；
- 恪守零重型依赖（[ADR-0011](../docs/adr/0011-zero-bloat-and-co-located-utilities.md)），无缝自适应接入项目已有的纯净安全 Redis 存储底座（[ADR-0022](../docs/adr/0022-dual-core-redis-and-distributed-coordination-architecture.md)），无 Redis 时自动平滑降级为内存会话；
- 深度融合 Core 模块 `CurrentUserProvider` SPI（[ADR-0024](../docs/adr/0024-enterprise-satoken-authentication-and-security-architecture.md)），全自动贯通持久层审计填充与行级数据权限。

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-satoken</artifactId>
</dependency>
```

---

## 2. 核心架构与设计拓扑

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

---

## 3. 强类型登录会话与审计贯通（LoginHelper & LoginUser）

### 场景痛点
原生 Sa-Token 的 `StpUtil.login(Object id)` 仅存储一个简单 ID 标量。在大型复杂业务中，开发者为了在后续流程中获取用户名、昵称、租户 ID、部门 ID、数据权限范围或角色权限列表，不得不频繁在 Session 中手工存取 `Map` 或对象，充斥着易出错的强制类型转换。同时，无法与持久层审计字段（`createBy`、`updateBy`）自动贯通，导致每次持久化操作都要手工从会话取出用户名并赋值。

### 核心代码示例（源自 `SaTokenSampleController.java` 与 `SampleSaTokenIntegrationTest.java`）

#### 1. 用户登录与登出
```java
@RestController
@RequestMapping("/api/auth")
public class SaTokenSampleController {

    @PostMapping("/login")
    public Result<String> login(@RequestBody LoginUser loginUser,
                                @RequestParam(value = "device", required = false) String device) {
        if (device != null && !device.isBlank()) {
            LoginHelper.login(loginUser, device);
        } else {
            LoginHelper.login(loginUser);
        }
        return Result.success(LoginHelper.getTokenValue());
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        LoginHelper.logout();
        return Result.success();
    }
}
```

#### 2. 业务方法中安全读取会话与复合主键解析
```java
@Service
public class UserService {

    public void doBusiness() {
        // 校验登录状态
        LoginHelper.checkLogin();

        // 读取当前登录用户 ID（支持 String 或 Long 类型安全获取；多租户场景自动对称剥离 "{tenantId}_" 前缀，返回纯净真实用户ID）
        String userId = LoginHelper.getUserId();
        Long userIdAsLong = LoginHelper.getUserIdAsLong();

        // 读取完整的强类型载荷对象
        LoginUser loginUser = LoginHelper.getLoginUser();
        log.info("当前用户: {}, 租户: {}, 部门: {}", loginUser.getUserName(), loginUser.getTenantId(), loginUser.getDeptId());

        // 判定是否具备超级管理员特权
        if (LoginHelper.isSuperAdmin()) {
            log.info("执行超级管理员特权逻辑");
        }
    }
}
```

#### 3. 双轨拦截与四维上下文对称清理 (`AtengSaTokenInterceptor`)
`ateng-boot-satoken` 注册受管拦截器 `AtengSaTokenInterceptor`，在 Web 请求生命周期中实现与底层 Core 模块无缝双向镜像贯通：
- **`preHandle` 对称注入**：不仅校验路由白名单与会话登录态，更自动将 Sa-Token 解析出的当前用户与租户无感注入 `UserContextHolder` 与 `TenantContextHolder`；
- **`afterCompletion` 严格双清理**：在请求结束视图渲染完毕后，强制执行上下文完全清空（`UserContextHolder.clear()` 与 `TenantContextHolder.clear()`），彻底杜绝 Web 容器工作线程复用引发的上下文串染与数据越权。

#### 4. MyBatis-Plus 自动审计零配置生效
当引入 `ateng-boot-satoken` 与 `ateng-boot-mybatis-plus` 时，系统自动注册 `SaTokenCurrentUserProvider` 接入 Core 模块的 `CurrentUserProvider` SPI。业务实体在入库或更新时，无需编写任何代码即可全自动填充 `create_by` 与 `update_by`：

```java
@Data
@TableName("sys_tenant_order")
public class TenantOrderEntity extends BaseEntity {
    private String orderNo;
    private BigDecimal amount;
    // createBy, updateBy, createTime, updateTime 继承自 BaseEntity，底层自动注入当前登录人与当前系统时间
}
```

### 预期控制台与响应呈现
- **登录成功响应（HTTP 200）**：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": "53f86e35-645c-44b4-82a1-127bcfb9ad61",
  "success": true
}
```
- **请求携带 Header**：
```http
satoken: 53f86e35-645c-44b4-82a1-127bcfb9ad61
```
- **MyBatis-Plus 自动填充底层执行 SQL（真实输出）**：
```text
==>  Preparing: INSERT INTO sys_tenant_order ( id, order_no, amount, create_by, update_by, create_time, update_time ) VALUES ( ?, ?, ?, ?, ?, ?, ? )
==> Parameters: 1(Long), ORD-AUDIT-88001(String), 999.50(BigDecimal), 88001(String), 88001(String), 2026-10-19 16:30:00.000(Timestamp), 2026-10-19 16:30:00.000(Timestamp)
<==    Updates: 1
```

---

## 4. 细粒度权限控制与统一异常转译

### 场景痛点
微服务应用通常要求多层安全防御：Web 路由拦截器负责拦截非法 URL 请求，AOP 注解负责在方法级别实施细粒度鉴权（支持基于方法参数、内部调用的拦截）。原生 Sa-Token 鉴权失败时往往抛出运行时异常，如果未深度适配，容易导致 HTTP 500 状态码或非标错误响应。

### 核心代码示例

```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    // 细粒度权限校验
    @GetMapping
    @SaCheckPermission("system:user:query")
    public Result<List<UserVO>> listUsers() {
        return Result.success(List.of(new UserVO(1L, "Ateng")));
    }

    // 细粒度角色校验
    @DeleteMapping("/{id}")
    @SaCheckRole("admin")
    public Result<Void> deleteUser(@PathVariable Long id) {
        return Result.success();
    }
}
```

### 预期统一异常契约呈现

当无权限访问时，内置 `SaTokenExceptionHandler` 遵循显式强类型契约（[ADR-0002](../docs/adr/0002-explicit-result-contract-no-response-body-advice.md)），统一返回 HTTP 200 + 403 业务错误，绝不泄露后端内部异常栈：

```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "code": 403,
  "msg": "无权访问：缺少所需权限码 [system:user:query]",
  "data": null,
  "success": false
}
```

控制台真实拦截日志：
```text
2026-10-19 16:30:00.123 [http-nio-8080-exec-1] WARN  i.g.a.b.s.h.SaTokenExceptionHandler : 鉴权拦截 - 缺少权限: permission=system:user:query
```

当未提供凭据或 Token 失效时，统一返回 HTTP 200 + 401 业务错误：

```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "code": 401,
  "msg": "未提供访问凭据 (Token)",
  "data": null,
  "success": false
}
```

控制台真实拦截日志：
```text
2026-10-19 16:30:00.125 [http-nio-8080-exec-2] WARN  i.g.a.b.s.h.SaTokenExceptionHandler : 鉴权拦截 - 会话未登录: type=-1, message=未能获取有效的 token
```

---

## 5. 企业级两级权限缓存与高并发条带锁防击穿

### 场景痛点
高并发系统中，每个标注 `@SaCheckPermission` 的接口每次调用都会触发 `StpInterface.getPermissionList()`。如果每次鉴权都穿透查询数据库或频繁发起 Redis 远程网络交互，高频接口的鉴权将成为系统性能瓶颈；此外，在高并发瞬时突发流量下，若缓存失效，大量并发线程同时穿透回源，将引发数据库/远程存储的**瞬时击穿**。

### 架构方案
`ateng-boot-satoken` 深度集成 `ateng-boot-redis` 的 `TwoLevelCacheManager`，并提供 `CachedStpInterface` 与 `PermissionCacheService`：
1. **L1 Caffeine 本地条带锁防击穿**：进程内缓存具备纳秒级响应，采用细粒度条带锁（`lockStripes`，默认 `64`）限制同一并发 Key 仅有单一线程回源，彻底消除瞬时击穿；
2. **L2 Redis 分布式两级缓存**：默认 `5m` 过期，内置 `__EMPTY__` 哨兵防穿透（默认 `60s`），以及 0~10% Jitter 动态随机抖动防雪崩；
3. **集群广播与主动驱逐**：权限发生变更时，通过 `PermissionCacheService` 毫秒级热清理本地及分布式缓存，并联动 Redis Pub/Sub 广播驱逐全网异地节点本地缓存。

### 核心代码示例（源自 `SaTokenSampleController.java`）

```java
@Service
public class RolePermissionManageService {

    @Autowired
    private PermissionCacheService permissionCacheService;

    // 当管理员修改了某个用户的角色或权限后
    public void updateUserPermissions(String userId) {
        // 1. 执行数据库权限关系更新...

        // 2. 毫秒级精准驱逐指定用户的两级权限角色缓存并广播全集群
        permissionCacheService.refreshUserPermissions(userId);
    }

    // 当管理员调整了某个角色的权限分配时
    public void updateRolePermissions(String roleCode) {
        // 毫秒级精准驱逐角色关联的所有权限缓存并广播全集群
        permissionCacheService.refreshRolePermissions(roleCode);
    }

    // 当系统管理员重置了全局权限树或大批量调整角色时
    public void refreshAllPermissions() {
        // 批量清空全局两级权限缓存并广播全集群
        permissionCacheService.clearAll();
    }
}
```

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
@RequestMapping("/api/multi")
public class SampleMultiAccountController {

    /**
     * 后台管理员登录（独立账号体系 admin，下发独立 Token 头 satoken-admin）
     */
    @PostMapping("/admin/login")
    public Result<String> adminLogin(@RequestBody LoginUser loginUser) {
        LoginHelper.login(loginUser, "admin");
        return Result.success(LoginHelper.getTokenValue("admin"));
    }

    /**
     * C端商城会员登录（独立账号体系 member，下发独立 Token 头 satoken-member）
     */
    @PostMapping("/member/login")
    public Result<String> memberLogin(@RequestBody LoginUser loginUser) {
        LoginHelper.login(loginUser, "member");
        return Result.success(LoginHelper.getTokenValue("member"));
    }

    /**
     * 获取管理员端信息（仅校验 admin 账号体系登录态）
     */
    @GetMapping("/admin/info")
    public Result<LoginUser> getAdminInfo() {
        LoginHelper.checkLogin("admin");
        return Result.success(LoginHelper.getLoginUser("admin"));
    }

    /**
     * 获取会员端信息（仅校验 member 账号体系登录态）
     */
    @GetMapping("/member/info")
    public Result<LoginUser> getMemberInfo() {
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
     * 分页查询在线用户列表（支持账号/姓名/凭据模糊搜索）
     */
    @GetMapping("/page")
    public Result<PageResult<OnlineUser>> getOnlinePage(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "10") long size) {
        PageResult<OnlineUser> result = LoginHelper.getOnlineUsers(page, size, keyword);
        return Result.success(result);
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
    public Result<Void> kickoutByDevice(@RequestParam("userId") String userId,
                                        @RequestParam("device") String device) {
        LoginHelper.kickoutByDevice(userId, device);
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
        timeout: 30d              # Token 有效期，强类型 Duration (默认: 30d)
        active-timeout: -1s       # Token 活跃临时有效期，强类型 Duration (默认: -1s 不限制)
        concurrent: true          # 是否允许同一账号多地并发登录 (默认: true)
        share: false              # 是否共用同一个 Token 凭据 (默认: false)
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
        l1-local-ttl: 5s          # L1 本地 Caffeine 缓存 TTL，强类型 Duration (默认: 5s)
        l1-max-size: 10000        # L1 本地 Caffeine 缓存最大容量条目数 (默认: 10000)
        jitter-min: 0.0           # 缓存过期时间随机抖动下限比例 (默认: 0.0)
        jitter-max: 0.10          # 缓存过期时间随机抖动上限比例 (默认: 0.10，防雪崩)
        lock-stripes: 64          # 本地回源并发条带锁槽位数 (默认: 64，防并发击穿)
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
