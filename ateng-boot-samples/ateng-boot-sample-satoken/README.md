# ateng-boot-sample-satoken 安全鉴权与会话治理端到端自验工程

> 专一 Sa-Token 鉴权、路由白名单拦截、Controller/Service 全域 AOP 声明式鉴权、编程式/注解式二级认证、多端设备治理、两级权限缓存热刷新、Spring 安全事件广播、Same-Token 内部零信任与 MyBatis-Plus 自动审计贯通 100% 端到端实测沙箱。

---

## 1. 场景定位与功能矩阵

在企业级微服务与单体系统中，安全鉴权与会话治理常面临如下痛点：
1. **外部中间件强耦合**：单机开发或本地自测时必须启动物理 Redis，否则启动崩溃或鉴权不可用；
2. **鉴权层次割裂**：仅在网关层拦截粗粒度 URL，业务层无法做到基于方法级角色/权限注解的细粒度防御；
3. **安全上下文与持久层割裂**：用户登录后获取到的主体信息无法透明贯通至数据库审计字段（`create_by` / `update_by`），需业务代码手工冗余赋值；
4. **多端治理与内网安全缺位**：缺乏多设备独立登录、精准踢人、防路径穿越与微服务内部调用防伪装保护。

`ateng-boot-sample-satoken` 提供完全离线自洽、零外部依赖的端到端可运行沙箱：
- **自适应存储降级**：无 Redis 环境下自动平滑降级为基于内存的高速会话存储，配合 H2 内存数据库秒级启动；
- **全域双轨安全防御**：兼顾双轨路由拦截白名单与 Controller/Service 全域 AOP 注解鉴权（`@SaCheckRole`、`@SaCheckPermission`、`@SaCheckSafe`）；
- **全链路审计流转**：通过 Core 模块的 `CurrentUserProvider` SPI 自动对接 MyBatis-Plus 审计处理器，插入实体自动回填当前登录人；
- **100% 离线自洽测试**：配套 31 项端到端集成测试（27 项综合业务鉴权自验 + 4 项全局严格路由校验），涵盖登录注销、二级认证、多端踢人/顶替、权限缓存热刷新、Same-Token 零信任、水平越权防御、声明式动态白名单、双维身份模拟审计、集群即时同步与登录防暴力破解等全部企业级特性。

---

## 2. 27 项综合业务自验场景全景表

| 序号 | 自验场景 | 覆盖核心机制 / API | 验证端点 / 动作 | 预期响应 / 校验点 |
| :--- | :--- | :--- | :--- | :--- |
| **01** | 未登录拦截防御 | `SaTokenExceptionHandler` / `NotLoginException` | `GET /api/auth/me` | HTTP 200 + code: 401，msg: `未提供访问凭据 (Token)` |
| **02** | 完整登录与注销生命周期 | `LoginHelper.login` / `logout` / 多租户复合 ID 解析 | `POST /api/auth/login`<br>`GET /api/auth/me`<br>`POST /api/auth/logout` | 成功下发 Token，透明剥离租户前缀获取用户 ID，注销后 Token 立即失效 (401) |
| **03** | 编程式二级安全认证全生命周期 | `LoginHelper.openSafe` / `checkSafe` / `closeSafe` | `GET /api/auth/safe-action`<br>`POST /api/auth/open-safe`<br>`POST /api/auth/close-safe` | 未开启报 403，开启后放行返回 200，关闭后恢复 403 拦截 |
| **04** | Controller 层 AOP 注解鉴权 | `@SaCheckRole` / `@SaCheckPermission` | `GET /api/auth/check-role`<br>`GET /api/auth/check-permission-forbidden` | 拥有角色/权限放行 200；缺少角色/权限精确拦截并返回 403 契约 |
| **05** | Service 业务层 AOP 穿透鉴权 | `SaAroundAnnotationPointcutAdvisor` 全域切面 | `GET /api/auth/service-role`<br>`GET /api/auth/service-permission` | Controller 透传调用 Service，Service 方法注解生效并完成鉴权拦截 |
| **06** | AOP 注解式二级安全认证 | `@SaCheckSafe("payment")` 声明式拦截 | `GET /api/auth/check-safe-annotation`<br>`POST /api/auth/open-safe` | 注解拦截未认证报 403，开启后放行通过 |
| **07** | 白名单公共路由匿名放行 | `AtengSaTokenInterceptor` 路径排除 | `GET /api/auth/public/ping` | 无需携带 Token，直接匿名放行返回 `pong` |
| **08** | CurrentUserProvider 审计回填 | `SaTokenCurrentUserProvider` + MyBatis-Plus | `POST /api/auth/order` | 插入实体后查询数据库，`create_by` 与 `update_by` 自动回填当前登录用户 ID |
| **09** | 多租户上下文生命周期协同 | `AtengSaTokenInterceptor` preHandle / afterCompletion | `GET /api/auth/current-tenant` | 请求内自动注入 `TenantContextHolder`，请求结束后强制清理现场防串染 |
| **10** | 两级权限缓存切片与热刷新 | `CachedStpInterface` + `PermissionCacheService` | `GET /api/auth/current-roles`<br>`POST /api/auth/cache/refresh-role` | 缓存权限数据，调用热刷新端点后毫秒级驱逐对应缓存 |
| **11** | 空角色/空权限哨兵防穿透 | `__EMPTY__` Sentinel 哨兵缓存机制 | `GET /api/auth/current-roles` (空权限用户) | 写入哨兵缓存防击穿，后续查询秒级返回空集合 |
| **12** | Spring 原生安全事件广播 | `SaTokenEventPublisher` -> `@EventListener` | `POST /api/auth/login`<br>`POST /api/auth/kickout` | 异步/同步广播 `SaLoginEvent` 与 `SaKickoutEvent`，监听器捕获事件详情 |
| **13** | Knife4j / OpenAPI 自动鉴权方案 | `SaTokenOpenApiCustomizer` 注入 SecurityScheme | `GET /v3/api-docs` | 校验 OpenAPI 规范包含 `satoken` 的 `apiKey` Header 鉴权方案与全局 Requirement |
| **14** | Same-Token 内部零信任保护 | `@CheckSameToken` + `SaCheckSameTokenAspect` | `GET /api/auth/internal/data` | 未携带/非法凭据拦截 403；携带有效 `SaSameUtil.getToken()` 正常放行 200 |
| **15** | 多端设备登录与按设备精准踢下线 | `LoginHelper.login(user, "PC")` / `kickout(userId, "PC")` | `POST /api/auth/login?device=PC`<br>`POST /api/auth/kickout` | 精准踢掉 PC 设备会话，被踢后访问返回 401 提示 `当前账号已被踢下线` |
| **16** | Sa-Token 防火墙非法路径穿越拦截 | `SaTokenFirewall` 规范化校验 | `GET /api/auth/test/..%2F..%2Fetc%2Fpasswd` | 路径穿越请求直接被防火墙拦截，统一返回 HTTP 200 + Result 400 契约 |
| **17** | 多端设备顶替下线与会话隔离 | `LoginHelper.replaced` 顶人机制 | `POST /api/auth/login`<br>`LoginHelper.replaced` | 账号被顶替后携带原 Token 访问返回 401 提示 `当前账号已在其他设备登录，您已被顶下线` |
| **18** | 水平越权防御（入参直接归属人） | `@CheckDataOwner(expr = "#dto.userId")` | `POST /api/auth/user/profile` | 自身 ID 放行 200；篡改他人 ID 立即被 AOP 切面拦截返回 403 契约 |
| **19** | 水平越权防御（间接资源反查 SPI） | `@CheckDataOwner(provider = SampleOrderDataOwnerProvider.class)` | `POST /api/auth/order/{orderId}/cancel` | 归属自身订单放行 200；他人订单经由 SPI 反查归属判定不匹配拦截 403 |
| **20** | 编程式数据归属前置断言 | `LoginHelper.checkSelf` / `checkBelongsToTenant` | `POST /api/auth/assert/self-tenant` | 自身与归属租户校验放行 200；跨租户或非本人抛出业务异常快速失败 403 |
| **21** | 声明式方法级匿名公开访问 | `@Anonymous` 方法注解 | `GET /api/auth/public/article/{id}` | 免 Token 携带直接放行 200，Spring MVC `{id}` 自适应转译 AntPath 匹配 |
| **22** | 声明式类级整类端点匿名访问 | `@Anonymous` 类注解 | `GET /api/anonymous/hello` | 控制器所有公共端点统一免鉴权放行 200 |
| **23** | SPI 动态白名单路由扩展 | `SaTokenWhitelistProvider` SPI 注入 | `GET /api/custom-spi/data` | 外部插件动态扩展的白名单端点免 Token 访问直接放行 200 |
| **24** | 跨请求会话模拟与双维审计贯通 | `LoginHelper.switchTo` / `endSwitch` | `POST /api/auth/impersonate/switch`<br>`GET /api/auth/impersonate/status` | 切换后 `getUserId()` 返回被模拟人，`originalUserId` 保持管理员，审计日志双维对齐 |
| **25** | 瞬态代码块安全模拟与栈式恢复 | `LoginHelper.runAs(userId, ...)` | `POST /api/auth/order/compensate` | 块内生效为目标用户并自动回填订单审计人，`finally` 确定性恢复现场 |
| **26** | 集群广播即时同步与自消费防卫 | `SaTokenClusterBroadcaster` + `SaTokenClusterSyncListener` | `GET /api/auth/cluster/instance-id`<br>`POST /api/auth/cluster/refresh-user` | 全网广播集群同步载荷，本机自消费静默丢弃防重入，异地广播精准失效本地缓存 |
| **27** | 登录防暴力破解失败惩罚与封禁 | `LoginFailureLockService` + `LoginHelper.disable` | `POST /api/auth/login-with-defense`<br>`GET /api/auth/defense/status` | 输错原子累计并返回剩余次数，达到上限联动原生封禁 403，登录成功对称清零 |

---

## 3. 本地启动与实测体验

### 1. 极速启动工程
```bash
mvn -pl ateng-boot-samples/ateng-boot-sample-satoken spring-boot:run
```

### 2. 用户登录（获取访问凭据 Token）
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "10086",
    "tenantId": "tenant_sz",
    "userAccount": "admin",
    "userName": "系统管理员",
    "roles": ["super_admin", "auditor"],
    "permissions": ["user:query", "user:export"],
    "extraData": {"deptCode": "TECH_DEV"}
  }'
```

**响应输出**：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": "53f86e35-645c-44b4-82a1-127bcfb9ad61",
  "success": true
}
```

### 3. 携带 Token 查询当前登录主体详情
```bash
curl -X GET http://localhost:8080/api/auth/me \
  -H "satoken: 53f86e35-645c-44b4-82a1-127bcfb9ad61"
```

**响应输出**：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "userId": "10086",
    "userAccount": "admin",
    "userName": "系统管理员",
    "tenantId": "tenant_sz",
    "roles": ["super_admin", "auditor"],
    "permissions": ["user:query", "user:export"],
    "extraData": {
      "deptCode": "TECH_DEV"
    }
  },
  "success": true
}
```

### 4. 验证 Service 业务层方法级权限拦截 (403)
```bash
curl -X GET http://localhost:8080/api/auth/service-permission \
  -H "satoken: 53f86e35-645c-44b4-82a1-127bcfb9ad61"
```

**响应输出**：
```json
{
  "code": 403,
  "msg": "无权访问：缺少所需权限码 [order:cancel]",
  "data": null,
  "success": false
}
```

### 5. 验证创建订单与持久层审计自动填充
```bash
curl -X POST http://localhost:8080/api/auth/order \
  -H "satoken: 53f86e35-645c-44b4-82a1-127bcfb9ad61" \
  -H "Content-Type: application/json" \
  -d '{
    "orderNo": "ORD-20261019-001",
    "amount": 888.88
  }'
```

**底层执行 SQL**：
```text
==>  Preparing: INSERT INTO sys_tenant_order ( id, order_no, amount, create_by, update_by, create_time, update_time ) VALUES ( ?, ?, ?, ?, ?, ?, ? )
==> Parameters: 1(Long), ORD-20261019-001(String), 888.88(BigDecimal), 10086(String), 10086(String), 2026-10-19 16:30:00(Timestamp), 2026-10-19 16:30:00(Timestamp)
<==    Updates: 1
```

---

## 4. 定向集成测试准出验证

按照 `ateng-boot` 规范，本工程测试用例 100% 内存自洽，无需外置 Redis 或 MySQL：

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-satoken -am -T 1C -nsu -B
```

**实测结果**：
```text
[INFO] Running io.github.atengk.boot.sample.satoken.SampleSaTokenCheckLoginIntegrationTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.450 s -- in io.github.atengk.boot.sample.satoken.SampleSaTokenCheckLoginIntegrationTest
[INFO] Running io.github.atengk.boot.sample.satoken.SampleSaTokenIntegrationTest
[INFO] Tests run: 27, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.994 s -- in io.github.atengk.boot.sample.satoken.SampleSaTokenIntegrationTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 31, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] BUILD SUCCESS
```
