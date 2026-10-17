# ateng-boot-satoken 安全鉴权与分布式会话治理套件

> 面向生产就绪的 Sa-Token 企业级安全基础设施与分布式会话治理底座，提供轻量无侵入鉴权、强类型 `LoginUser` / `LoginHelper` 载荷模型、双轨拦截防御体系、Core / MyBatis-Plus 审计全链路贯通、两级权限缓存与动态热刷新、Spring 原生安全事件总线、微服务内部零信任 Same-Token 调用保护及 OpenAPI / Knife4j 鉴权弹窗无侵入自动化集成。

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-satoken` 深度融合 Sa-Token `1.46.0` 核心能力，恪守 `ateng-boot` 规范：
- 严格遵循显式强类型契约（ADR-0002），将全量鉴权异常统一转译为 HTTP 200 + `Result<T>` 格式；
- 恪守零重型依赖（ADR-0011），无缝自适应接入项目已有的纯净安全 Redis 存储底座（ADR-0022），无 Redis 时自动平滑降级为内存会话；
- 深度融合 Core 模块 `CurrentUserProvider` SPI（ADR-0024），全自动贯通持久层审计填充与行级数据权限。

### 方式一：独立子模块引入（推荐配合 BOM）
```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-satoken</artifactId>
    <version>0.0.5</version>
</dependency>
```

### 方式二：一键全家桶引入
```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-all</artifactId>
    <version>0.0.5</version>
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
                .id(1001L)
                .tenantId("tenant_001")
                .deptId(2001L)
                .username(dto.getUsername())
                .nickname("张三")
                .userType("SYS_USER")
                .roles(Set.of("admin", "manager"))
                .permissions(Set.of("system:user:query", "system:user:export"))
                .extra(Map.of("clientIp", "127.0.0.1"))
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

#### 2. 业务方法中安全读取会话
```java
@Service
public class UserService {

    public void doBusiness() {
        // 读取当前登录用户 ID（未登录自动抛出 NotLoginException）
        Long userId = LoginHelper.getUserId();

        // 读取完整的强类型载荷对象
        LoginUser loginUser = LoginHelper.getLoginUser();
        log.info("当前用户: {}, 租户: {}, 部门: {}", loginUser.getUsername(), loginUser.getTenantId(), loginUser.getDeptId());

        // 判定是否具备超级管理员权限
        if (LoginHelper.isSuperAdmin()) {
            log.info("执行超级管理员特权逻辑");
        }
    }
}
```

#### 3. MyBatis-Plus 自动审计零配置生效
当引入 `ateng-boot-satoken` 与 `ateng-boot-mybatis-plus` 时，系统自动注册 `SaTokenCurrentUserProvider` 接入 Core 模块的 `CurrentUserProvider` SPI。业务实体在入库或更新时，无需编写任何代码即可全自动填充 `create_by` 与 `update_by`：

```java
@Data
@TableName("sys_user")
public class UserEntity extends BaseEntity {
    private String username;
    // createBy, updateBy, createTime, updateTime 由 BaseEntity 统一定义并由 MyBatis-Plus 自动填充
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
  "msg": "无权限访问: 缺少权限 [system:user:query]",
  "data": null,
  "success": false
}
```

当未登录或 Token 失效时，统一返回 HTTP 200 + 401 业务错误：

```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "code": 401,
  "msg": "访问未授权: 未提供有效的身份令牌",
  "data": null,
  "success": false
}
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

---

## 7. 微服务内部零信任调用保护（Same-Token）

### 场景痛点
在微服务架构中，内部服务之间往往有专用的 Feign / RPC 接口。若缺乏调用凭据验证，攻击者或外网恶意请求一旦绕过 API 网关，就可直接向内部微服务发送伪造请求，造成重大越权安全事故。

### 核心代码示例

#### 1. 服务端接口声明零信任保护
只需在 Controller 类或方法上标注 `@CheckSameToken`：

```java
@RestController
@RequestMapping("/internal/api")
@CheckSameToken // 拦截并校验微服务内部调用凭证
public class InternalSyncController {

    @PostMapping("/sync-data")
    public Result<String> syncData() {
        return Result.success("内部同步成功");
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
  "code": 401,
  "msg": "微服务内部调用凭证无效或已过期",
  "data": null,
  "success": false
}
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
      include-paths:              # 拦截器鉴权生效的 URL 路径列表 (默认: /**)
        - "/**"
      exclude-paths:              # 自动放行的白名单 URL 路径列表
        - "/api/auth/login"
        - "/doc.html"
        - "/webjars/**"
        - "/v3/api-docs/**"
        - "/favicon.ico"
      same-token-enabled: true    # 是否启用微服务内部 Same-Token 零信任调用校验 (默认: true)
      permission-cache:           # 两级权限缓存配置
        enabled: true             # 是否启用权限两级缓存 (默认: true)
        local-expire-seconds: 5   # L1 进程本地微缓存过期时间 (默认: 5 秒)
        redis-expire-seconds: 3600 # L2 Redis 缓存过期时间 (默认: 3600 秒)
        jitter-ratio: 0.1         # 防雪崩随机抖动比例 (默认: 0.1 即 0%~10% 随机增量)
```

---

## 10. 架构决策与设计参考

- [ADR-0024: 企业级 Sa-Token 安全鉴权与分布式会话治理架构](../../docs/adr/0024-enterprise-satoken-authentication-and-security-architecture.md)
- [ADR-0002: 显式统一响应契约](../../docs/adr/0002-explicit-result-contract-no-response-body-advice.md)
- [ADR-0012: 生命周期分离的数据安全模型与内存真明文原则](../../docs/adr/0012-lifecycle-separated-data-security.md)
- [ADR-0022: 双核驱动 Redis 基础设施与分布式协同架构](../../docs/adr/0022-dual-core-redis-and-distributed-coordination-architecture.md)
