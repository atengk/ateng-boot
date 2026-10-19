# ateng-boot-satoken 安全鉴权与分布式会话治理套件

> 面向生产就绪的 Sa-Token 企业级安全基础设施与分布式会话治理底座，提供轻量无侵入鉴权、强类型 `LoginUser` / `LoginHelper` 载荷模型、双轨拦截防御体系、Core / MyBatis-Plus 审计全链路贯通、两级权限缓存与动态热刷新、Spring 原生安全事件总线、微服务内部零信任 Same-Token 调用保护及 OpenAPI / Knife4j 鉴权弹窗无侵入自动化集成。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **会话登录** | 强类型用户登录 | `LoginHelper` | `ateng.boot.satoken.enabled` | `login(LoginUser)` / `login(LoginUser, String)` / `login(LoginUser, SaLoginParameter)` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **会话注销** | 会话安全注销 | `LoginHelper` | - | `logout()` / `logoutByTokenValue(String)` / `logoutByDevice(String)` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **设备治理** | 账号踢人下线 | `LoginHelper` | - | `kickout(Object loginId)` / `kickout(Object loginId, String deviceType)` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **设备治理** | 账号顶替下线 | `LoginHelper` | - | `replaced(Object loginId)` / `replaced(Object loginId, String deviceType)` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **账号封禁** | 账号/服务封禁与解封 | `LoginHelper` | - | `disable(Object, Duration)` / `disable(Object, String, Duration)` / `untieDisable(Object)` / `untieDisable(Object, String)` / `isDisable` / `checkDisable` / `getDisableTime` | `LoginHelperTest` |
| **二级认证** | 编程式二级安全认证 | `LoginHelper` | - | `openSafe(Duration)` / `openSafe(int)` / `openSafe(String, Duration)` / `isSafe()` / `checkSafe()` / `closeSafe()` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **状态查询** | 登录状态与令牌查询 | `LoginHelper` | - | `isLogin()` / `checkLogin()` / `getTokenValue()` / `getTokenValueByLoginId(Object)` / `getTokenValueByLoginId(Object, String)` / `getTokenTimeout()` / `getSessionTimeout()` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **载荷存取** | 强类型实体存取 | `LoginHelper` | - | `setLoginUser(LoginUser)` / `getLoginUser()` / `getLoginUser(Class<T>)` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **身份感知** | 用户/租户身份提取 | `LoginHelper` | - | `getUserId()` / `getUserIdAsLong()` / `getUserAccount()` / `getUserName()` / `getTenantId()` / `getDeptId()` / `buildCompositeLoginId` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **权限断言** | 角色与特权判定 | `LoginHelper` | - | `getRoles()` / `getPermissions()` / `hasRole(String)` / `hasPermission(String)` / `isSuperAdmin()` | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **用户载荷** | 工业级强类型载荷 | `LoginUser` | - | 标配 `@SaJsonType`、链式 setter 与 `extraData` 扩展字典 | `LoginHelperTest` / `SampleSaTokenIntegrationTest` |
| **路由拦截** | 双轨路由拦截与白名单 | `AtengSaTokenInterceptor` / `SaTokenWebMvcConfigurer` | `ateng.boot.satoken.interceptor.*`<br>`ateng.boot.satoken.white-list` | `isAnnotation(false)` 专精路径白名单防漏，`preHandle` 注入上下文，`afterCompletion` 强制清空 | `AtengSaTokenInterceptorTest` / `SampleSaTokenIntegrationTest` |
| **注解鉴权** | 细粒度声明式鉴权 | `@SaCheckLogin`<br>`@SaCheckRole`<br>`@SaCheckPermission`<br>`@SaCheckSafe`<br>`@SaCheckDisable` | - | AOP 切面统一承接 Controller 与 Service 边界方法鉴权，彻底消除双重校验损耗 | `SampleSaTokenIntegrationTest` |
| **内部零信任** | 微服务内部防伪装调用 | `@CheckSameToken`<br>`SaCheckSameTokenAspect` | `ateng.boot.satoken.same-token.enabled`<br>`ateng.boot.satoken.same-token.header-name`<br>`ateng.boot.satoken.same-token.token-value` | 校验内网微服务调用凭据，防网关绕过探测；支持配置开关与自定义请求头 | `SaCheckSameTokenAspectTest` / `SampleSaTokenIntegrationTest` |
| **权限缓存** | 两级权限缓存治理 | `CachedStpInterface`<br>`PermissionCacheService` | `ateng.boot.satoken.permission-cache.enabled`<br>`ateng.boot.satoken.permission-cache.timeout`<br>`ateng.boot.satoken.permission-cache.empty-ttl` | `[用户ID -> 角色集]` + `[角色ID -> 权限集]` 两级缓存，空哨兵防穿透与 Jitter 防雪崩，毫秒级热刷新 | `CachedStpInterfaceTest` / `SampleSaTokenIntegrationTest` |
| **业务 SPI** | 角色与权限业务加载器 | `UserRolePermissionLoader` | - | `loadRolesByUserId(String)` / `loadPermissionsByRole(String)` / `loadDirectPermissionsByUserId(String)` | `CachedStpInterfaceTest` / `SampleSaTokenIntegrationTest` |
| **Core 贯通** | 当前操作人/租户 SPI 适配 | `SaTokenCurrentUserProvider`<br>`SaTokenCurrentTenantProvider` | - | 自动向 Core 贯通当前用户与租户，驱动 MyBatis-Plus 审计自动填充与多租户行级隔离 | `SampleSaTokenIntegrationTest` |
| **事件总线** | 原生安全事件广播 | `SaTokenEventPublisher` | - | 桥接广播 `SaLoginEvent`、`SaLogoutEvent`、`SaKickoutEvent`、`SaReplacedEvent`、`SaDisableEvent` 等 Spring 原生事件 | `SaTokenEventPublisherTest` / `SampleSaTokenIntegrationTest` |
| **OpenAPI 适配** | Knife4j 鉴权弹窗自动化 | `SaTokenOpenApiCustomizer` | `ateng.boot.satoken.openapi.enabled`<br>`ateng.boot.satoken.openapi.token-name` | 自动在 `/v3/api-docs` 与 Knife4j 界面右上角挂载 Authorize 鉴权弹窗 | `SaTokenOpenApiCustomizerTest` / `SampleSaTokenIntegrationTest` |
| **分布式存储** | 纯净 JSON 会话存储驱动 | `SaTokenRedisDao` | - | 基于 `StringRedisTemplate` 存储，杜绝 `@class` 反序列化白名单漏洞，无 Redis 自动平滑降级 | `SaTokenRedisDaoTest` / `SampleSaTokenIntegrationTest` |
| **异常转译** | 统一显式 Result 响应 | `SaTokenExceptionHandler` | - | 将未登录 (401)、权限不足 (403)、服务封禁 (403) 等转译为 HTTP 200 + `Result<Void>` 显式契约 | `SampleSaTokenIntegrationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-satoken` 深度融合 Sa-Token `1.46.0` 核心能力，恪守 `ateng-boot` 规范：
- 严格遵循显式强类型契约（ADR-0002），将全量鉴权异常统一转译为 HTTP 200 + `Result<T>` 格式；
- 恪守零重型依赖（ADR-0011），无缝自适应接入项目已有的纯净安全 Redis 存储底座（ADR-0022），无 Redis 时自动平滑降级为内存会话；
- 深度融合 Core 模块 `CurrentUserProvider` SPI（ADR-0024），全自动贯通持久层审计填充与行级数据权限。

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
│  (全局路由匹配、Token 校验、上下文) │                     │     (细粒度方法鉴权 / SameToken)   │
└────────────────┬────────────────┘                     └────────────────┬─────────────────┘
                 │                                                       │
                 └───────────────────────┬───────────────────────────────┘
                                         ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                     ateng-boot-satoken 核心引擎与安全门面                          │
├────────────────────────────────┬─────────────────────────────────────────────────┤
│ • LoginHelper (强类型会话门面)   │ • CachedStpInterface (L1微缓存 + L2 Redis两级缓存) │
│ • LoginUser (工业级用户载荷模型) │ • PermissionCacheService (多租户权限热刷新与驱逐) │
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

### 核心代码示例

#### 1. 用户登录与登出
```java
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @PostMapping("/login")
    public Result<String> login(@RequestBody LoginDTO dto) {
        // 1. 模拟校验用户名密码并组装工业级强类型 LoginUser
        LoginUser user = LoginUser.builder()
                .userId("1001")
                .tenantId("tenant_001")
                .deptId("2001")
                .userAccount(dto.getUsername())
                .userName("张三")
                .userType("SYS_USER")
                .roles(Set.of("admin", "manager"))
                .permissions(Set.of("system:user:query", "system:user:export"))
                .extraData(Map.of("clientIp", "127.0.0.1"))
                .build();

        // 2. 强类型极简登录（底层自动将 LoginUser 存入会话 Session）
        LoginHelper.login(user);

        // 3. 获取生成并下发的 Token
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

当无权限访问时，内置 `SaTokenExceptionHandler` 遵循显式强类型契约（ADR-0002），统一返回 HTTP 200 + 403 业务错误，绝不泄露后端内部异常栈：

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

## 5. 企业级两级权限缓存与动态热刷新

### 场景痛点
高并发系统中，每个标注 `@SaCheckPermission` 的接口每次调用都会触发 `StpInterface.getPermissionList()`。如果每次鉴权都穿透查询数据库或频繁发起 Redis 远程网络交互，高频接口的鉴权将成为系统性能瓶颈；此外，用户权限变更后若无法即时感知或驱逐缓存，会导致脏权限或权限收回不及时。

### 架构方案
`ateng-boot-satoken` 提供 `CachedStpInterface` 与 `PermissionCacheService`：
1. **L1 本地内存微缓存**：进程内缓存 5 秒（防同一次请求或短时间连击内的重复拉取）；
2. **L2 Redis 分布式缓存**：默认 1 小时过期，内置 `__EMPTY__` 哨兵防穿透，以及 0~10% Jitter 动态抖动防雪崩；
3. **动态热刷新与主动驱逐**：权限发生变更时，通过 `PermissionCacheService` 毫秒级热清理本地及分布式缓存。

### 核心代码示例

```java
@Service
public class RolePermissionManageService {

    @Autowired
    private PermissionCacheService permissionCacheService;

    // 当管理员修改了某个用户的角色或权限后
    public void updateUserPermissions(Long userId) {
        // 1. 执行数据库权限关系更新...

        // 2. 毫秒级精准驱逐指定用户的两级权限角色缓存
        permissionCacheService.clearUserCache(userId);
    }

    // 当系统管理员重置了全局权限树或大批量调整角色时
    public void refreshAllPermissions() {
        // 批量清空全局权限缓存
        permissionCacheService.clearAllCache();
    }
}
```

---

## 6. Spring 原生安全事件总线

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

### 预期安全审计日志呈现（真实控制台输出）
```text
2026-10-19 16:30:00.100 [http-nio-8080-exec-1] INFO  i.g.a.b.s.s.l.SampleSecurityEventListener : 【安全事件监听】收到用户登录事件: userId=88001, token=53f86e35-645c-44b4-82a1-127bcfb9ad61
2026-10-19 16:30:00.200 [http-nio-8080-exec-2] WARN  i.g.a.b.s.s.l.SampleSecurityEventListener : 【安全事件监听】收到用户踢下线事件: userId=88001, token=null
```

---

## 7. 微服务内部零信任调用保护（Same-Token）

### 场景痛点
在微服务架构中，内部服务之间往往有专用的 Feign / RPC 接口。若缺乏调用凭据验证，攻击者或外网恶意请求一旦绕过 API 网关，就可直接向内部微服务发送伪造请求，造成重大越权安全事故。

### 核心代码示例

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

#### 2. 调用方 Feign 请求携带 Same-Token
```java
@Configuration
public class FeignClientConfig {

    @Bean
    public RequestInterceptor sameTokenRequestInterceptor() {
        return template -> {
            // 自动注入微服务统一 Same-Token 签名
            template.header(SaSameUtil.SAME_TOKEN, SaSameUtil.getToken());
        };
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

控制台真实拦截日志：
```text
2026-10-19 16:30:00.300 [http-nio-8080-exec-3] DEBUG i.g.a.b.s.a.SaCheckSameTokenAspect : 执行微服务内部 Same-Token 零信任安全校验
2026-10-19 16:30:00.301 [http-nio-8080-exec-3] WARN  i.g.a.b.s.h.SaTokenExceptionHandler : 鉴权拦截 - 微服务内部 Same-Token 无效: message=无效Same-Token：null
```

---

## 8. OpenAPI / Knife4j 鉴权弹窗无侵入自动化

### 场景痛点
在集成 Knife4j 或 Swagger UI 时，默认情况下接口文档页面无法便捷输入 Token，开发者测试接口需要反复在 Headers 中手动填写 `satoken` 请求头，体验极其繁琐。

### 自动化效果
当工程中引入 `springdoc-openapi-starter-webmvc-ui` 时，`ateng-boot-satoken` 自动装配 `SaTokenOpenApiCustomizer`：
1. 自动在 OpenAPI Components 中注册 `SecurityScheme`（名称：`satoken`，类型：`APIKEY`，位置：`HEADER`）；
2. 自动注入全局 `SecurityRequirement`。
3. 打开 Knife4j UI 页面右上角直接展示「Authorize / 授权」锁图标，填入 Token 后全局接口全自动携带。

---

## 9. 配置项全景表（application.yml）

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
      white-list:                 # 全局路由白名单路径列表（内置基础端点，底层自动与 interceptor.excludes 合并去重）
        - "/doc.html"
        - "/webjars/**"
        - "/swagger-resources/**"
        - "/v3/api-docs/**"
        - "/favicon.ico"
        - "/actuator/**"
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
      permission-cache:           # 权限与角色多级缓存治理配置
        enabled: true             # 是否开启权限码与角色缓存 (默认: true)
        timeout: 5m               # 权限与角色缓存有效期，强类型 Duration (默认: 5m，兼容旧版 timeout-seconds)
        empty-ttl: 60s            # 空权限/空角色防穿透哨兵缓存有效期，强类型 Duration (默认: 60s，兼容旧版 empty-ttl-seconds)
      openapi:                    # OpenAPI / Knife4j 鉴权弹窗配置
        enabled: true             # 是否自动为 Knife4j / Swagger 注入鉴权弹窗 (默认: true)
        token-name: satoken       # 鉴权组件名称与请求头 Key (默认: satoken)
```

---

## 10. 生产扩展与接管实践 (Customization & Extensibility)

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
        // 从数据库查询用户拥有的角色编码集合
        return userRoleService.getRoleCodesByUserId(userId);
    }

    @Override
    public List<String> loadPermissionsByRole(String roleCode) {
        // 从数据库查询角色拥有的权限码集合
        return rolePermissionService.getPermissionCodesByRole(roleCode);
    }

    @Override
    public List<String> loadDirectPermissionsByUserId(String userId) {
        // 从数据库查询用户额外直属特权集合
        return Collections.emptyList();
    }
}
```

### 2. 覆盖默认会话存储实现 (`SaTokenDao`)
`ateng-boot-satoken` 在检测到 Redis 依赖时自动装配 `SaTokenRedisDao`，未检测到时自动降级为单机内存字典。若企业需要对接专属分布式缓存（如集群版 Redis、多级缓存代理），只需声明同类型 `@Bean`：

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

### 3. 异步监听安全审计生命周期事件
所有鉴权事件由 `SaTokenEventPublisher` 统一桥接为标准 Spring 原生 `ApplicationEvent`，业务方可通过 `@Async` 与 `@EventListener` 结合实现零阻塞日志入库：

```java
@Component
public class AsyncSecurityAuditConsumer {

    @Async
    @EventListener
    public void onLogin(SaLoginEvent event) {
        // 异步归档登录日志至 ES 或数据库
    }

    @Async
    @EventListener
    public void onKickout(SaKickoutEvent event) {
        // 触发风控审计推送
    }
}
```

---

## 11. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 1. 严格无状态会话与零状态泄漏防线
- **单例无状态**：所有安全组件（`LoginHelper`、`PermissionCacheService`、`AtengSaTokenInterceptor`、`SaTokenRedisDao`）均保持严格无状态，无任何成员变量共享可变业务数据；
- **全生命周期对称清理**：`AtengSaTokenInterceptor` 在 `afterCompletion` 的 `finally` 块中严格执行 `UserContextHolder.clear()` 与 `TenantContextHolder.clear()`，杜绝 Web 容器工作线程池复用引发的上下文串染与数据越权。

### 2. 多端多设备独立登录与精准踢人/顶替下线
- **多端共存**：支持 `LoginHelper.login(user, "PC")`、`LoginHelper.login(user, "APP")` 多端独立会话并存；
- **精准隔离**：支持全局踢人 `kickout(userId)`、按设备踢人 `kickout(userId, deviceType)`、顶替下线 `replaced(userId, deviceType)`，客户端被踢出或顶替后统一转译为 HTTP 200 + code: 401 并附带明确语义提示。

### 3. 多租户复合 LoginId 防碰撞与透明还原
- **防碰撞**：多租户场景下自动合成 `{tenantId}_{userId}` 复合 LoginId，彻底消除不同租户相同数字用户主键（如 `1`）的会话踩踏；
- **透明还原**：业务调用 `LoginHelper.getUserId()` 与 `getUserIdAsLong()` 时，底层自动识别并剥离租户前缀，透明还原为原始用户 ID。

### 4. 微服务内部 Same-Token 零信任调用防穿透
- **防网关穿透**：针对内网微服务专属接口标注 `@CheckSameToken`，未携带合法校验凭据的外部探测直接被 403 阻断；
- **配置开闭守卫**：配置 `ateng.boot.satoken.same-token.enabled = false` 时切面实施快速放行，确保本地单机调试与灰度环境平滑过渡。

### 5. 权限缓存防穿透与防雪崩机制
- **防穿透**：当用户无角色或角色无权限时，写入 `__EMPTY__` 哨兵并赋予短 TTL（默认 60s），阻断空值对数据库的频繁击穿；
- **防雪崩**：缓存写入时对超时时间附加 0%~10% 的动态 Jitter 随机抖动，杜绝海量用户集中登录后缓存同一秒失效引发数据库瞬间雪崩。

---

## 12. 架构决策与设计参考

- [ADR-0024: 企业级 Sa-Token 安全鉴权与分布式会话治理架构](../../docs/adr/0024-enterprise-satoken-authentication-and-security-architecture.md)
- [ADR-0002: 显式统一响应契约](../../docs/adr/0002-explicit-result-contract-no-response-body-advice.md)
- [ADR-0012: 生命周期分离的数据安全模型与内存真明文原则](../../docs/adr/0012-lifecycle-separated-data-security.md)
- [ADR-0022: 双核驱动 Redis 基础设施与分布式协同架构](../../docs/adr/0022-dual-core-redis-and-distributed-coordination-architecture.md)

