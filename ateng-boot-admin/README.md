# ateng-boot-admin

> 面向生产就绪的 **Spring Boot Admin (SBA 3.3.4) 企业级微服务运维监控看板与安全防御 Starter**。坚持纯原生轻量化、100% 排除 Spring Security，原生集成 Sa-Token 多角色控制台鉴权与防爆破锁定、入出站双层 SSRF 防火墙与可信发现审查、高危 Actuator 端点硬阻断与 RBAC 写守卫、零库脱敏操作审计、防抖/发版静默/雪崩聚合/持续重提醒/动态维护静默状态机、Fail-Open Redis 集群协调，以及低基数 Micrometer/HealthIndicator 自观测闭环。

---

## 0. 核心能力与配置全景矩阵（100% 索引）

| 能力域 | 核心能力与对外契约 | 关键组件 / SPI / 端点 / 配置前缀 | 锚定验证源头 | 定位锚点 |
| :--- | :--- | :--- | :--- | :--- |
| 起步与架构 | BOM / 显式版本双模引入与五级过滤器链排序 | `AtengAdminServerAutoConfiguration`、`AtengAdminServerDefenseAutoConfiguration` | `AtengAdminServerAutoConfigurationTest` | [第 1 章](#1-快速起步与过滤器链拓扑) |
| 控制台认证与 RBAC | 原生登录表单、多账号配置、`{bcrypt}`、三角色 RBAC（`VIEWER`/`OPERATOR`/`ADMIN`）与主体桥接 | `AdminLoginController`、`AdminAuthProvider`、`AdminConsolePrincipal`、`AdminPrincipalBridgeFilter` | `AdminRealClientEndToEndTest` / `SampleAdminAuthProvider` | [2.1 节](#21-控制台多角色-rbacbcrypt-与主体桥接) |
| 登录防爆破与登出 | 账号+IP 双轨失败滑窗锁定、可信代理提取（`trusted-proxies`） | `AdminLoginDefenseService`、`ateng.boot.admin.server.login-defense.*` | `AdminLoginDefenseServiceTest` | [2.2 节](#22-登录防爆破锁定与反代可信-ip) |
| 控制台 REST 门面 | 当前主体信息、审计检索、运行时维护静默增删查 | `AdminConsoleController`（`{context-path}/console/{me,audits,silences}`） | `AdminConsoleControllerTest` / `AdminSecurityConsoleController` | [2.3 节](#23-控制台-rest-门面-me--audits--silences) |
| 零库脱敏操作审计 | 10 类审计动作、自动凭据掩码与有界环形仓库/领域事件 | `AdminAuditService`、`AdminAuditLogRepository`、`AdminAuditEvent` | `AdminConsoleControllerTest` / `AdminSecurityConsoleController` | [2.4 节](#24-零库结构化脱敏操作审计) |
| 入出站 SSRF 与发现准入 | 有界可复位请求体（413）、四层 SSRF（含 `100.64/10`、`fc00::/7`）、三种 DNS 模式、出站二次重检与发现过滤 | `AdminSsrfGuardFilter`、`AdminOutboundSsrfFilterFunction`、`AdminAdmissionInstanceFilter`、`AdminInstanceAdmissionPolicy` | `AdminAdmissionController` / `AdminRealClientEndToEndTest` | [2.5 节](#25-入出站-ssrf-防火墙可信发现与生命周期双因子鉴权) |
| 高危端点与 RBAC 守卫 | `blocked-endpoints` 硬阻断（含 GET）、全局写开关与 `VIEWER` 写拦截 | `AdminEndpointGuardFilter`、`AdminWriteGuardFilter` | `AdminGovernanceController` / `AdminSecurityConsoleController` | [2.6 节](#26-高危端点硬阻断与-rbac-写守卫) |
| 出站 Actuator 凭据级联 | 抑制原生重复 Basic 标头，按优先级自适应注入凭据 | `AtengActuatorHttpHeadersProvider`、`BasicAuthHttpHeaderProvider` 抑制 | `AdminGovernanceController` / `AdminRealClientEndToEndTest` | [2.7 节](#27-多模态出站-actuator-凭据穿透与跨模块协同) |
| 客户端握手与自保边界 | `X-Admin-Client-Secret` 自动附加、Client `/actuator/**` Token 保护（`ateng-boot-observability` 归属） | `AdminClientSecretHttpRequestInterceptor`、`ActuatorEndpointSecurityFilter` | `AdminRealClientEndToEndTest` | [2.7 节](#27-多模态出站-actuator-凭据穿透与跨模块协同) |
| 僵尸清理与有界事件存储 | `ReentrantLock` 定时驱逐失效实例并联动清洗事件与通知状态 | `ZombieInstanceCleanupScheduler`、`AtengInMemoryEventStore` | `AdminLifecycleController` / `ZombieInstanceCleanupSchedulerTest` | [3.1 节](#31-僵尸实例清理与有界内存事件存储) |
| 多阶防风暴告警状态机 | 毫秒防抖、发版静默、黑白名单、持续周期重提醒与雪崩折叠聚合 | `AtengAdminNotificationNotifier`、`ateng.boot.admin.server.alert.*` | `AdminAlertController` / `AtengAdminNotificationNotifierTest` | [3.2 节](#32-防抖告警发版静默持续重提醒与雪崩聚合状态机) |
| 按服务路由与维护静默 | 服务模式通配路由至指定渠道与 `@所有人`、运行时维护静默窗口 | `AlertRouteProperties`、`AdminSilenceManager`、`SilenceRule` | `AdminConsoleControllerTest` / `DefaultAdminSilenceManagerTest` | [3.3 节](#33-按服务告警路由与运行时动态维护静默) |
| 集群协同与单机降级 | 多节点防重复告警与互斥清理（无 Redis 内存自洽，Redis 异常 Fail-Open） | `AdminClusterCoordinator`、`RedisAdminClusterCoordinator` | `AtengAdminServerAutoConfigurationTest` | [3.4 节](#34-fail-open-集群协调与单机内存降级) |
| 低基数自观测探针 | 10 项禁止无界动态标签指标与监控中心 Actuator 健康指示器 | `AdminMetricsBinder`（`ateng.admin.*`）、`AdminServerHealthIndicator`（`adminServer`） | `AdminMetricsBinderTest` / `AdminServerHealthIndicatorTest` / `AdminRealClientEndToEndTest` | [3.5 节](#35-低基数-micrometer-指标与-adminserver-健康探针) |
| 全量配置与排错指南 | 全属性类型、默认值、单位与常见陷阱排查 | `AtengAdminServerProperties` | `AtengAdminServerAutoConfigurationTest` | [第 4 章](#4-全量配置项字典) & [第 5 章](#5-常见故障排查与验证基准) |

---

## 1. 快速起步与过滤器链拓扑

### 1.1 引入依赖（BOM 管控 / 显式版本双模坐标）

1. **方式一：由 `ateng-boot-bom` 统一纳管版本（推荐，免写 `<version>`）**

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-admin</artifactId>
</dependency>
```

2. **方式二：未引入 BOM 时声明显式版本（支持独立按需引入）**

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-admin</artifactId>
    <version>1.2.0</version>
</dependency>
```

> **提示**：服务端需在启动类上标注 Spring Boot Admin 官方启用注解 `@EnableAdminServer`，以激活 `AdminServerMarkerConfiguration.Marker` 并触发 `AtengAdminServerDefenseAutoConfiguration` 与 `AtengAdminServerAutoConfiguration`。

### 1.2 最小生产可用配置示例

```yaml
spring:
  application:
    name: ops-admin-server
  boot:
    admin:
      context-path: /admin

ateng:
  boot:
    admin:
      server:
        enabled: true
        # 强烈建议配置多角色 users 列表（配置后自动禁用默认 admin/admin 后门）
        users:
          - username: sec-admin
            password: "{bcrypt}$2a$10$REPLACE_WITH_REAL_BCRYPT_HASH"
            roles: [ADMIN]
          - username: sre-operator
            password: "{bcrypt}$2a$10$REPLACE_WITH_REAL_BCRYPT_HASH"
            roles: [OPERATOR]
          - username: dev-viewer
            password: "{bcrypt}$2a$10$REPLACE_WITH_REAL_BCRYPT_HASH"
            roles: [VIEWER]
        # 与所有接入端约定统一的高强度共享准入密钥
        client-secret: YOUR_CLIENT_SECRET
        # 监控中心反向代理抓取微服务 /actuator/** 时兜底注入的令牌
        default-actuator-token: YOUR_ACTUATOR_TOKEN
        # 默认开启防私网或结合允许 CIDR 约束（内网集群按需关闭或设 allowed-cidrs）
        allow-local-instances: false
        block-private-networks: true
        allowed-cidrs:
          - "10.20.0.0/16"
        dns-resolution-mode: FAIL_CLOSED
        cleanup:
          enabled: true
          interval: 1h
          zombie-threshold: 24h
```

### 1.3 Servlet 防线与 SBA 代理执行优先级顺序

为防止鉴权绕过或流体在校验前被提前消费，`ateng-boot-admin` 的 5 层请求管道严格按以下 `@Order` 链式递增执行：

```text
HTTP 入站请求
  │
  ▼  [-104] SaTokenContextFilter       (Sa-Token 上下文绑定，确保 StpUtil.isLogin() 就绪)
  ▼  [-102] AdminSsrfGuardFilter       (校验 POST /instances 负载/密钥/SSRF 与 DELETE /instances/{id} 双因子)
  ▼  [-100] SaServletFilter            (锚定 {context-path}/** 会话鉴权，放行 login/logout/assets/instances 生命周期)
  ▼  [-95]  AdminPrincipalBridgeFilter (将 AdminConsolePrincipal.name() 注入 HttpServletRequest.getUserPrincipal())
  ▼  [-90]  AdminEndpointGuardFilter   (硬阻断 heapdump/shutdown/restart、全局只读开关与 VIEWER 写操作)
  │
  ▼  SBA WebController / AdminConsoleController / InstanceWebClient (+ AdminOutboundSsrfFilterFunction [@Order(15)])
```

> **重要不变量**：所有控制台接口（`/login`、`/logout`、`/console/**`）与生命周期端点均严格锚定 `${spring.boot.admin.context-path}` 前缀，防止应用下发 `server.servlet.context-path` 或反向代理改写前缀时脱离 `SaServletFilter` 守卫圈。

---

## 2. 安全治理、RBAC 鉴权、审计与 SSRF 纵深防御

### 2.1 控制台多角色 RBAC、`{bcrypt}` 与主体桥接

- **机制说明**：保留一维认证方法 `AdminAuthProvider.authenticate(username, password)` 向后兼容，新增默认方法 `authenticatePrincipal(username, password)` 签发不可变 `AdminConsolePrincipal`（携带 `VIEWER` / `OPERATOR` / `ADMIN` 角色集合）。默认实现 `DefaultPropertiesAdminAuthProvider` 支持明文及 `{bcrypt}` 散列校验；只要配置了 `ateng.boot.admin.server.users` 非空列表，自动彻底关闭单账号默认兜底，杜绝默认口令后门。登录后由 `AdminPrincipalBridgeFilter` 将用户名桥接给 SBA 原生 UI 右上角及操作日志。
- **自定义 SPI 示例（1:1 提取自 [SampleAdminAuthProvider](../ateng-boot-samples/ateng-boot-sample-admin/src/main/java/io/github/atengk/boot/sample/admin/provider/SampleAdminAuthProvider.java)）**：若自定义认证 Bean 并需支持多角色控制，**务必同时覆写 `authenticatePrincipal`**（仅覆写 `authenticate` 会回退为默认 `ADMIN` 角色）：

```java
@Component
public class SampleAdminAuthProvider implements AdminAuthProvider {

    private static final Set<String> LOCKED_ACCOUNTS = Set.of("locked-operator");
    private final DefaultPropertiesAdminAuthProvider defaultProvider;

    public SampleAdminAuthProvider(AtengAdminServerProperties properties) {
        this.defaultProvider = new DefaultPropertiesAdminAuthProvider(properties);
    }

    @Override
    public boolean authenticate(String username, String password) {
        return authenticatePrincipal(username, password).isPresent();
    }

    @Override
    public Optional<AdminConsolePrincipal> authenticatePrincipal(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return Optional.empty();
        }
        String normalizedUser = username.trim();
        if (LOCKED_ACCOUNTS.contains(normalizedUser)) {
            return Optional.empty();
        }
        return defaultProvider.authenticatePrincipal(normalizedUser, password);
    }
}
```

- **预期效果与避坑**：未配置 `users` 且维持默认 `admin/admin` 或 `YOUR_CLIENT_SECRET` 占位符时，启动期 `SmartInitializingSingleton` 会打印 `WARN` 强告警；SBA 控制台各账号严格遵循最小权限，例如 `VIEWER` 仅可查看仪表盘、日志级别与审计列表。

### 2.2 登录防爆破锁定与反代可信 IP

- **机制说明**：`AdminLoginDefenseService` 按规范化用户名与客户端 IP 双维计数登录失败次数（默认窗口 `15m` 内允许连续失败 `5` 次，触发后锁定 `15m` 并跳转 `/login?locked=true` 且禁用登录按钮）。仅当直连来源 `request.getRemoteAddr()` 命中 `login-defense.trusted-proxies` CIDR（基于 Core `CidrMatcher`）时，才信任 `X-Forwarded-For` / `X-Real-IP` 首段真实 IP；否则忽略伪造请求头。支持在开启 `cluster.enabled=true` 时接入 Redis 同步锁定状态。
- **配置示例**：

```yaml
ateng:
  boot:
    admin:
      server:
        login-defense:
          enabled: true
          max-failures: 5
          failure-window: 15m
          lock-duration: 15m
          trusted-proxies:
            - "10.0.0.0/8"
            - "127.0.0.1/32"
```

- **预期效果与避坑**：未将反向代理网关 CIDR 列入 `trusted-proxies` 前，恶意客户端携带 `X-Forwarded-For: 1.2.3.4` 无法绕过源 IP 计数锁定，亦无法封死任意第三方无辜 IP。

### 2.3 控制台 REST 门面（`/me` / `/audits` / `/silences`）

- **机制说明**：`AdminConsoleController` 在 `{spring.boot.admin.context-path}/console` 下暴露统一返回 `Result<T>` 脱敏 VO 的轻量管控接口（可通过 `console-api-enabled: false` 关闭）：
  - `GET /console/me`（`VIEWER+`）：返回当前主体用户名与角色，自动对敏感属性打码；
  - `GET /console/audits`（`VIEWER+`）：按 `action`、`actor`、`limit` 倒序检索内存有界审计仓库；
  - `GET /console/silences`（`VIEWER+`）：查看生效中的动态维护静默窗口；
  - `POST /console/silences`（`OPERATOR`/`ADMIN`）：创建静默规则，入参 `AdminSilenceCreateRequest` 受 `@Valid` 与 `@NotBlank` 校验，返回 `AdminSilenceRuleVO`；
  - `DELETE /console/silences/{id}`（`OPERATOR`/`ADMIN`）：主动撤销维护静默窗口。
- **端到端调用示例（1:1 提取自 [AdminRealClientEndToEndTest](../ateng-boot-samples/ateng-boot-sample-admin/src/test/java/io/github/atengk/boot/sample/admin/AdminRealClientEndToEndTest.java)）**：

```http
POST /admin/console/silences
Content-Type: application/json
Cookie: satoken=YOUR_SESSION_TOKEN

{"servicePattern":"ateng-boot-sample-admin","duration":"PT3M","reason":"端到端维护演练"}
```

- **预期效果与避坑**：`VIEWER` 账号调用 `POST/DELETE /console/silences` 将直接收到 HTTP `403`，同时由 `AdminAuditService` 记入 `ENDPOINT_BLOCKED` 审计并累加 `ateng.admin.endpoint.blocked.total` 指标。

### 2.4 零库结构化脱敏操作审计

- **机制说明**：`AdminAuditService` 针对关键安全运维动作生成不可变 `AdminAuditRecord` 并同步发布 Spring 领域事件 `AdminAuditEvent`，同时投递至 `AdminAuditLogHandler` 扩展链（默认实现 `DefaultAdminAuditLogHandler` 写入容量上限由 `audit.max-records` 控制的 `AdminAuditLogRepository` 环形缓冲并输出结构化脱敏日志）。涵盖 10 类标准 `AdminAuditAction`：
  `LOGIN_SUCCESS`、`LOGIN_FAILURE`、`LOGIN_LOCKED`、`LOGOUT`、`ADMISSION_REJECTED`、`INSTANCE_DEREGISTERED`、`ENDPOINT_BLOCKED`、`ACTUATOR_PROXY_MUTATION`、`SILENCE_CREATED`、`SILENCE_REVOKED`。
- **事件监听扩展示例**：

```java
@Component
public class SecurityAuditAlertListener {

    @EventListener
    public void onAuditEvent(AdminAuditEvent event) {
        AdminAuditRecord record = event.getRecord();
        if (record.getAction() == AdminAuditAction.LOGIN_LOCKED) {
            // 可按需联动外发高危安全预警，record.getMaskedActor() 与 detail 已完成自动脱敏
        }
    }
}
```

- **预期效果与避坑**：`AdminAuditRecord` 自动对 `actor` 执行用户名脱敏（如 `sr******e`），并对 `path`、`detail` 中包含 `password`/`token`/`secret`/`authorization` 或 `https://user:pass@host` 的片段自动替换为 `******`，严守“审计日志零明文凭据”红线。

### 2.5 入出站 SSRF 防火墙、可信发现与生命周期双因子鉴权

- **机制说明**：
  1. **入站可复读与防 SSRF**：`AdminSsrfGuardFilter` 通过 `ResettableContentCachingRequestWrapper` 对 `POST {context-path}/instances` 执行有界流缓存（超出 `max-registration-payload-size` 默认 `1MB` 直接拒收返回 `413`）；`DefaultAdminInstanceAdmissionPolicy` 拦截空请求体、含 `@` 用户凭据的 URL、回环/链路本地/`100.64.0.0/10`（含阿里云 IMDS `100.100.100.200`）/`fc00::/7`（含 AWS IPv6 IMDS `fd00:ec2::254`）及跨编码 IP 变体。
  2. **三模 DNS 核验与出站二次重检**：通过 `dns-resolution-mode` 控制域名解析核验策略（`DISABLED`、`FAIL_OPEN` 默认对可解析 IP 严格查验、对离线不可解析假域名放行，生产建议切为 `FAIL_CLOSED`）；`AdminOutboundSsrfFilterFunction` 在 SBA `InstanceWebClient` 真正发起 HTTP 请求前（`@Order(15)`）再次执行地址与 DNS 校验，防御 DNS Rebinding 攻击。
  3. **Discovery 发现实例过滤与注销双因子**：`AdminAdmissionInstanceFilter` 为 `source=discovery` 发现节点免除 HTTP 密钥并执行地址合规核验（独占上报发现准入指标，不与 HTTP `AdminSsrfGuardFilter` 重复计数）；单段注销 `DELETE {context-path}/instances/{id}` 强制验证 `X-Admin-Client-Secret` 或 `OPERATOR`/`ADMIN` 登录态。
- **自定义准入 SPI 示例（提取自 [SampleInstanceAdmissionPolicy](../ateng-boot-samples/ateng-boot-sample-admin/src/main/java/io/github/atengk/boot/sample/admin/policy/SampleInstanceAdmissionPolicy.java)）**：

```java
@Component
public class SampleInstanceAdmissionPolicy extends DefaultAdminInstanceAdmissionPolicy {

    public SampleInstanceAdmissionPolicy(AtengAdminServerProperties properties) {
        super(properties);
    }

    @Override
    public AdminAdmissionResult evaluate(InstanceRegistration registration, HttpServletRequest request) {
        if (registration != null && StringUtils.hasText(registration.getName())
                && registration.getName().trim().toLowerCase(Locale.ROOT).startsWith("forbidden-")) {
            return AdminAdmissionResult.rejected(HttpStatus.FORBIDDEN,
                    "禁止高危黑名单前缀服务接入看板: " + registration.getName());
        }
        return super.evaluate(registration, request);
    }
}
```

- **预期效果与避坑**：启用 `block-private-networks: true` 时若内网或虚拟网卡（如 `100.64.x.x`）需合法注册，须通过 `allowed-cidrs` 显式放行指定网段；非法的 `ftp://` 或含 `user:pass@` URL 将被立即返回 `403`。

### 2.6 高危端点硬阻断与 RBAC 写守卫

- **机制说明**：`AdminEndpointGuardFilter`（继承并兼容旧版 `AdminWriteGuardFilter`）拦截 `{context-path}/instances/{id}/actuator/**` 代理路径，按序执行三道防线：1) 命中 `blocked-endpoints` 时无论 HTTP 方法一律返回 `403`；2) 非幂等写操作（`POST`/`PUT`/`DELETE`/`PATCH`）受全局 `enable-write-operations` 开关限制；3) 非幂等写操作强制校检 `OPERATOR` 或 `ADMIN` 角色，`VIEWER` 直接阻断。
- **配置示例**：

```yaml
ateng:
  boot:
    admin:
      server:
        enable-write-operations: true
        blocked-endpoints:
          - heapdump
          - shutdown
          - restart
```

- **预期效果与避坑**：默认清单保留 `env`/`beans`/`metrics`/`health` 只读访问以确保 SBA UI 面板正常展示，同时将 `heapdump`/`shutdown`/`restart` 物理切断；生产冻结发版期可将 `enable-write-operations` 动态置为 `false` 进入全看板只读保护模式。

### 2.7 多模态出站 Actuator 凭据穿透与跨模块协同

- **机制说明**：`AtengAdminServerDefenseAutoConfiguration` 自动占位覆盖 SBA 默认注册的 `BasicAuthHttpHeaderProvider`（使之返回空标头），确保仅由权威 `AtengActuatorHttpHeadersProvider` 按 `metadata Basic -> metadata Bearer/JWT -> 自定义 Token 请求头 (metadata actuator-token -> service-actuator-tokens -> default-actuator-token)` 优先级生成认证头，彻底消除重复 `Authorization` 冲突。客户端接入 `ateng-boot-observability` 时，由 `AdminClientSecretHttpRequestInterceptor` 附加 `X-Admin-Client-Secret` 与 `actuator-token` 元数据，客户端自身端点由 `ActuatorEndpointSecurityFilter` 守卫（参见 [ateng-boot-observability README](../ateng-boot-observability/README.md)）。
- **配置示例（1:1 提取自样例工程 [application.yml](../ateng-boot-samples/ateng-boot-sample-admin/src/main/resources/application.yml)）**：

```yaml
ateng:
  boot:
    admin:
      server:
        actuator-token-header: X-Actuator-Token
        default-actuator-token: YOUR_DEFAULT_ACTUATOR_TOKEN
        service-actuator-tokens:
          payment-core-service: YOUR_PAYMENT_ACTUATOR_TOKEN
    observability:
      security:
        token: YOUR_CLIENT_ACTUATOR_TOKEN
      admin-client:
        enabled: true
        client-secret: YOUR_CLIENT_SECRET
```

- **预期效果与避坑**：在 [AdminRealClientEndToEndTest](../ateng-boot-samples/ateng-boot-sample-admin/src/test/java/io/github/atengk/boot/sample/admin/AdminRealClientEndToEndTest.java) 中已实测验证：未带 Token 直连客户端 `/actuator/env` 被 `401/403` 拦截，而管理员通过 `/admin/instances/{id}/actuator/env` 反代访问时由服务端自动注入 `X-Actuator-Token` 成功穿透返回 `200`。

---

## 3. 生命周期治理、告警防风暴状态机与自观测探针

### 3.1 僵尸实例清理与有界内存事件存储

- **机制说明**：`AtengInMemoryEventStore` 默认约束单实例聚合事件上限（`max-log-size-per-aggregate=100`）与已注销实例保留上限（`max-retained-deregistered=1000`），按 FIFO 淘汰旧记录防长周期运行堆溢出。开启 `cleanup.enabled=true` 后，`ZombieInstanceCleanupScheduler` 使用专用单线程调度器与 `ReentrantLock` 扫描超时 `OFFLINE`/`UNKNOWN` 实例并执行带超时控制的注销驱逐，同时回调清除事件存储及通知器残留状态。
- **配置示例**：

```yaml
ateng:
  boot:
    admin:
      server:
        event-store:
          max-log-size-per-aggregate: 100
          max-retained-deregistered: 1000
        cleanup:
          enabled: true
          interval: 1h
          zombie-threshold: 24h
```

- **预期效果与避坑**：单线程隔离调度 + `ReentrantLock` 彻底杜绝 JDK 21+ 虚拟线程载体 Pinning；未开启 `cleanup.enabled=true` 时不注册清理调度器 Bean，保持零后台线程开销。

### 3.2 防抖告警、发版静默、持续重提醒与雪崩聚合状态机

- **机制说明**：`AtengAdminNotificationNotifier` 内置完整 `LifecycleState`（`CREATED`/`RUNNING`/`STOPPED`）+ `ReentrantLock` 状态机（释放锁后再推送网络 I/O），按序流转：1) 准入过滤（`included-services`/`excluded-services`/`notify-statuses`）；2) 发版静默（`deregistration-silence-duration=60s`）与动态维护静默屏蔽；3) 毫秒防抖（`debounce-duration=30s`）滤除瞬时抖动；4) 持续故障周期重提醒（默认开启后每隔 `15m` 最多提醒 `3` 次）；5) 微窗口雪崩折叠聚合（`window=10s`、`threshold=5`）。
- **配置示例**：

```yaml
ateng:
  boot:
    admin:
      server:
        debounce-duration: 30s
        alert:
          deregistration-silence-duration: 60s
          console-base-url: "https://ops.example.com/admin"
          excluded-services:
            - "silent-*"
          notify-statuses:
            - "DOWN"
            - "OFFLINE"
          reminder:
            enabled: true
            interval: 15m
            max-count: 3
          storm-aggregation:
            enabled: true
            window: 10s
            threshold: 5
```

- **预期效果与避坑**：故障恢复 `UP` 后会自动撤销待发重提醒并推送绿色“故障恢复”卡片；使用静态配置 `excluded-services` 面向长期忽略服务，临时变更为避免重启应改用 3.3 节维护静默。

### 3.3 按服务告警路由与运行时动态维护静默

- **机制说明**：通过 `alert.routes` 可按 `service-pattern` 将特定业务线告警路由到指定渠道（`channel`），并精细控制 `mention-all`、`mention-mobiles` 与 `mention-user-ids`；运维值班期可通过 `AdminSilenceManager` SPI 或 `{context-path}/console/silences` 增删查带 TTL 维护静默规则，完全正交于静态名单。
- **配置与调用示例**：

```yaml
ateng:
  boot:
    admin:
      server:
        alert:
          routes:
            - service-pattern: "payment-*"
              channel: "payment-oncall-bot"
              mention-all: true
              mention-user-ids: ["oncall-uid-01"]
```

- **预期效果与避坑**：维护静默规则默认有效期由 `cluster.silence-rule-default-ttl`（默认 `2h`）兜底，支持到期自动清理并同步触发告警状态机阻断重复推送。

### 3.4 Fail-Open 集群协调与单机内存降级

- **机制说明**：`AdminClusterCoordinator` 协调多副本下的“僵尸清理互斥锁”、“告警发信去重窗”及“维护静默共享”。默认装配 `InMemoryAdminClusterCoordinator`；仅当 `cluster.enabled=true` 且容器中存在 `StringRedisTemplate` 时激活 `RedisAdminClusterCoordinator`。当 Redis 异常不可达时严格执行 **Fail-Open** 原则放行主流程。
- **配置示例**：

```yaml
ateng:
  boot:
    admin:
      server:
        cluster:
          enabled: true
          key-prefix: "ateng:admin:"
          cleanup-lock-ttl: 30s
          alert-dedup-ttl: 60s
          silence-rule-default-ttl: 2h
```

- **预期效果与避坑**：未接入 Redis 的单机沙箱即使同时存在 `spring-data-redis` 依赖，在 `cluster.enabled=false` 时仍确定性运行单机协调器，零连接异常告警。

### 3.5 低基数 Micrometer 指标与 `adminServer` 健康探针

- **低基数防线**：`AdminMetricsBinder`（需 classpath 存在 Micrometer `MeterBinder`）在绑定时**严格禁止使用 `instanceId`、URL、IP 或用户名作为 Metric Tag**，确保 Prometheus 等时序库零维度爆炸。`AdminServerHealthIndicator` 则作为 Actuator `adminServer` 组件实时汇总实例与调度器健康状态。
- **全量指标与健康探针详情清单**：

| 指标名称 | 类型 | 允许 Tag 枚举值 | 语义说明 |
| :--- | :--- | :--- | :--- |
| `ateng.admin.instances.registered` | Gauge | 无 | 当前已注册实例总数 |
| `ateng.admin.instances` | Gauge | `status`: `UP`/`DOWN`/`OFFLINE`/`UNKNOWN`/`RESTRICTED` | 按健康状态分组的实例数 |
| `ateng.admin.notifier.pending.tasks` | Gauge | 无 | 防抖窗口待确认任务数 |
| `ateng.admin.notifier.alerted.instances` | Gauge | 无 | 处于告警未恢复状态的实例数 |
| `ateng.admin.silences.active` | Gauge | 无 | 当前生效中的维护静默规则数 |
| `ateng.admin.admission.requests` | FunctionCounter | `outcome`: `admitted`/`rejected` | 注册/发现准入判定放行与拒绝次数 |
| `ateng.admin.login.attempts` | FunctionCounter | `outcome`: `success`/`failure`/`locked` | 控制台登录成功、失败及锁定次数 |
| `ateng.admin.endpoint.blocked.total` | FunctionCounter | 无 | 端点守卫与控制台越权拦截次数 |
| `ateng.admin.alerts` | FunctionCounter | `type`: `debounced`/`sent`/`reminder`/`storm_aggregated`/`silenced` | 告警状态机各分支事件计数 |
| `ateng.admin.zombies.evicted.total` | FunctionCounter | 无 | 僵尸调度器累计驱逐实例数 |

```json
{
  "components": {
    "adminServer": {
      "status": "UP",
      "details": {
        "registeredInstances": 1,
        "upInstances": 1,
        "nonUpInstances": 0,
        "notifierRunning": true,
        "pendingAlerts": 0,
        "alertedInstances": 0,
        "cleanupEnabled": true,
        "cleanupRunning": true,
        "activeSilences": 0
      }
    }
  }
}
```

- **预期效果与避坑**：当 `InstanceRepository` 读取超时失败、通知调度器关闭（`notifier.isSchedulerShutdown()`）或当 `cleanup.enabled=true` 但 `cleanupScheduler` 未运行时，`adminServer` 探针将准确报告 `DOWN` 并附带 `reason` 说明。

---

## 4. 全量配置项字典

配置前缀：`ateng.boot.admin.server`（时间类全部使用 `java.time.Duration`，容量类使用 `org.springframework.util.unit.DataSize`）。

| 配置键 | 类型 | 默认值 | 说明 |
| :--- | :--- | :--- | :--- |
| `enabled` | `boolean` | `true` | 是否启用监控中心企业级安全增强自动装配 |
| `username` | `String` | `admin` | 单账号回退管理员名（配置 `users` 非空后失效） |
| `password` | `String` | `admin` | 单账号回退口令（支持 `{bcrypt}` 前缀） |
| `users[i].username` / `password` / `roles` | `List` | `[]` / `[VIEWER]` | 多角色控制台账号清单（`VIEWER` / `OPERATOR` / `ADMIN`） |
| `users[i].enabled` / `attributes` | `boolean` / `Map` | `true` / `{}` | 账号启用状态与非敏感附加展示属性 |
| `client-secret` | `String` | `YOUR_CLIENT_SECRET` | 注册与注销通信共享准入密钥（`X-Admin-Client-Secret`） |
| `default-actuator-token` | `String` | `null` | 全局兜底 Actuator 代理令牌 |
| `actuator-token-header` | `String` | `X-Actuator-Token` | 注入微服务的自定义令牌请求头名 |
| `service-actuator-tokens` | `Map` | `{}` | 按 `spring.application.name` 划分的差异化 Actuator Token |
| `allow-local-instances` | `boolean` | `true` | 是否放行回环与本机局域网测试实例 |
| `block-private-networks` | `boolean` | `false` | 是否阻断 RFC1918 私网注册 |
| `allowed-url-patterns` | `List<String>` | `[]` | 注册与出站 URL 正则白名单 |
| `allowed-cidrs` / `blocked-cidrs` | `List<String>` | `[]` | CIDR 允许与显式拦截清单 |
| `dns-resolution-mode` | `DnsResolutionMode` | `FAIL_OPEN` | 域名解析核验策略：`DISABLED` / `FAIL_OPEN` / `FAIL_CLOSED` |
| `outbound-ssrf-check-enabled` | `boolean` | `true` | 是否在 SBA `InstanceWebClient` 出站前二次重检 SSRF |
| `enable-write-operations` | `boolean` | `true` | 全局 Actuator 代理变更写操作（POST/PUT/DELETE/PATCH）开关 |
| `blocked-endpoints` | `List<String>` | `heapdump,shutdown,restart` | 物理硬阻断高危子端点清单（含 GET） |
| `console-api-enabled` | `boolean` | `true` | 是否启用 `{context-path}/console/**` REST 管理端点 |
| `max-registration-payload-size` | `DataSize` | `1MB` | `POST /instances` 注册请求体最大容量限制 |
| `debounce-duration` | `Duration` | `30s` | 故障防抖窗口时长 |
| `cleanup.enabled` | `boolean` | `false` | 是否启用 `ZombieInstanceCleanupScheduler` |
| `cleanup.interval` | `Duration` | `1h` | 僵尸实例定时扫描间隔 |
| `cleanup.zombie-threshold` | `Duration` | `24h` | `OFFLINE`/`UNKNOWN` 实例判定超时阈值 |
| `alert.included-services` | `List<String>` | `[]` | 告警服务白名单通配模式 |
| `alert.excluded-services` | `List<String>` | `[]` | 告警服务黑名单通配模式 |
| `alert.notify-statuses` | `List<String>` | `DOWN,OFFLINE` | 触发告警的目标状态列表 |
| `alert.deregistration-silence-duration` | `Duration` | `60s` | Kubernetes 优雅注销后的发版静默窗口时长 |
| `alert.console-base-url` | `String` | `null` | 告警卡片附带的控制台跳转根地址 |
| `alert.reminder.enabled` | `boolean` | `false` | 是否开启持续未恢复故障周期重提醒 |
| `alert.reminder.interval` | `Duration` | `15m` | 持续重提醒最小间隔 |
| `alert.reminder.max-count` | `int` | `3` | 单次故障轮次最大重提醒次数 |
| `alert.storm-aggregation.enabled` | `boolean` | `false` | 是否开启雪崩多实例告警折叠聚合 |
| `alert.storm-aggregation.window` | `Duration` | `10s` | 雪崩统计滑窗 |
| `alert.storm-aggregation.threshold` | `int` | `5` | 触发聚合摘要的阈值条数 |
| `alert.routes[i].{service-pattern,channel,mention-all,mention-mobiles,mention-user-ids}` | `List` | `[]` | 按服务模式匹配的渠道与 `@提醒` 路由规则 |
| `event-store.max-log-size-per-aggregate` | `int` | `100` | 单实例保留事件日志上限 |
| `event-store.max-retained-deregistered` | `int` | `1000` | 已注销实例快照保留上限 |
| `cluster.enabled` | `boolean` | `false` | 是否启用基于 `StringRedisTemplate` 的 Fail-Open 集群协调 |
| `cluster.key-prefix` | `String` | `ateng:admin:` | Redis 协调键统一前缀 |
| `cluster.cleanup-lock-ttl` | `Duration` | `30s` | 分布式僵尸清理互斥锁 TTL |
| `cluster.alert-dedup-ttl` | `Duration` | `60s` | 多副本防重复发信去重窗 |
| `cluster.silence-rule-default-ttl` | `Duration` | `2h` | 维护静默规则未显式指定 TTL 时的默认有效期 |
| `login-defense.enabled` | `boolean` | `true` | 是否启用登录防暴力破解保护 |
| `login-defense.max-failures` | `int` | `5` | 连续失败触发防爆破锁定次数 |
| `login-defense.failure-window` | `Duration` | `15m` | 登录失败滑窗 |
| `login-defense.lock-duration` | `Duration` | `15m` | 锁定持续时长 |
| `login-defense.trust-proxy-headers` | `boolean` | `false` | 是否无条件信任代理源 IP 头（不建议开启，推荐配置 `trusted-proxies`） |
| `login-defense.trusted-proxies` | `List<String>` | `[]` | 允许提取代理源 IP 头的可信网段 CIDR |
| `login-defense.ip-whitelist` | `List<String>` | `127.0.0.1,::1` | 免受防爆破计数与锁定的白名单 IP/CIDR |
| `login-defense.max-tracked-entries` | `int` | `10000` | 单机防爆破内存跟踪表容量上限 |
| `audit.enabled` | `boolean` | `true` | 是否启用控制台结构化操作审计 |
| `audit.max-records` | `int` | `500` | 环形内存审计仓库最大留存条数 |

---

## 5. 常见故障排查与验证基准

### 5.1 常见问题快速排查

1. **为什么自定义 `AdminAuthProvider` 后，Configured `VIEWER` 用户依然拥有 `ADMIN` 权限？**
   - 自检：确认实现类是否同时覆写了 `authenticatePrincipal(String username, String password)`。仅覆写旧版返回 `boolean` 的 `authenticate(...)` 时，默认方法会将通过验证的主体包装为 `ADMIN` 角色。
2. **为什么宿主机存在 VPN / 虚拟网卡（如 `100.64.x.x`）时，`prefer-ip=true` 注册失败并返回 403 SSRF 拦截？**
   - 原因：`DefaultAdminInstanceAdmissionPolicy` 为防御云厂商元数据（`100.100.100.200`）而硬阻断 CGNAT 段 `100.64.0.0/10`。解决：内网真实服务可通过 `spring.boot.admin.client.instance.service-base-url` 锚定固定内网业务地址，或将合规网段显式列入 `ateng.boot.admin.server.allowed-cidrs`。
3. **为什么访问 `/console/silences` 报 404 或绕过了鉴权？**
   - 核查 `spring.boot.admin.context-path`（样例配置为 `/admin`），请求路径须为 `/admin/console/silences`，且 `ateng.boot.admin.server.console-api-enabled` 未设为 `false`。
4. **为什么同时出现重复的 `Authorization` 标头？**
   - 检查项目中是否自定义声明了覆盖 `basicAuthHttpHeadersProvider` 的 Bean；默认装配已通过空实现抑制 SBA 原生 `BasicAuthHttpHeaderProvider`，全部凭据统一由 `AtengActuatorHttpHeadersProvider` 生成。

### 5.2 离线闭环验证基准

本模块及 1:1 体验工程已通过二阶完整回归测试：

```bash
mvn test -pl ateng-boot-admin,ateng-boot-samples/ateng-boot-sample-admin -am -T 2 -nsu -B
```

- `ateng-boot-admin`：全部单元测试 100% 通过（涵盖 RBAC、防爆破、入出站 SSRF、DNS 三模核验、审计、通知防抖/重提醒/雪崩聚合/静默、Redis Fail-Open 协调、低基数 Micrometer 指标与 HealthIndicator）；
- `ateng-boot-sample-admin`：全部黑盒与真实服务端到端集成测试 100% 通过（涵盖 5 大领域 Controller 与 `RANDOM_PORT` 真实 SBA Client 注册/双向 Token 穿透/`heapdump` 拦截/RBAC/自观测探针全链路）；
- 扩展参考：[领域术语表 (CONTEXT.md)](./CONTEXT.md)、[架构决策记录 (ADR-0055)](../docs/adr/0055-spring-boot-admin-production-monitoring-and-security-architecture.md)、[1:1 样例工程 README](../ateng-boot-samples/ateng-boot-sample-admin/README.md)。
