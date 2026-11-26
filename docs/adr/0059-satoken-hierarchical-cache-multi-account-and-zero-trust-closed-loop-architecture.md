# 59. 企业级 Sa-Token 级联两级缓存、多账号体系与微服务零信任全闭环架构决策

## 背景与痛点

在 [ADR-0024](./0024-enterprise-satoken-authentication-and-security-architecture.md) 中，`ateng-boot` 确立了 `ateng-boot-satoken` 的基础安全底座架构。随着企业级应用复杂度提高、多终端业务融合以及高并发吞吐诉求升级，既有实现在工业级生产环境中显现出深水区瓶颈与架构断层：

1. **两级权限缓存名不副实与网络 IO 穿透**：
   - 既有 `PermissionCacheService` 在检测到 Redis 时仅读写 Redis，完全绕过了本地内存；导致高并发鉴权时每个请求均需发起 1~3 次 Redis 网络交互（增加 1~3ms 延迟）；且异地集群同步监听到失效事件后，清理的也是未曾使用的空本地缓存；
2. **多账号体系治理断层 (Multi-Account / Multi-StpLogic)**：
   - 大型企业级系统通常要求管理后台（`admin`）与移动端/开放平台（`member` / `open`）完全隔离 Token 命名空间、独立配置超时与互斥规则；
   - 现有 `LoginHelper` 内部硬编码绑定全局默认 `StpUtil`（`loginType="login"`），无法支撑多账号体系的强类型操作与纯净 JSON 会话封包；
3. **Same-Token 微服务调用链路“半残门面”**：
   - 现有实现仅有服务端的 `@CheckSameToken` AOP 切面防卫，缺乏跨服务 RPC/HTTP 调用时的客户端自动拦截器，导致调用方业务必须手动获取并注入请求头，破坏了工业级 Starter 的开箱即用性；
4. **防暴力破解维度单一引发拒绝服务 (DoS) 风险**：
   - 既有 `LoginFailureLockService` 仅按 `account` 维度单向累计失败并锁定账号，当恶意攻击者从单 IP 批量扫描爆破系统账号时，可能导致大面积正常业务用户被恶意锁死。

---

## 核心架构与决策内容

经由 `/grill-with-docs` 架构深度质询推演，确立以下四大核心决策：

```mermaid
flowchart TD
    subgraph MultiAccount["1. 多账号体系解耦 (Multi-StpLogic)"]
        LH["LoginHelper"]
        AdminLogic["StpLogic('admin')"]
        MemberLogic["StpLogic('member')"]
        DefaultLogic["StpLogic('login')"]
        LH -->|login(loginType, user)| SaMgr["SaManager 动态路由与懒加载"]
        SaMgr --> AdminLogic
        SaMgr --> MemberLogic
        SaMgr --> DefaultLogic
    end

    subgraph TwoLevelCache["2. 级联两级权限缓存 (L1+L2)"]
        StpInt["CachedStpInterface"]
        PermService["PermissionCacheService"]
        RedisModuleAdapter{"检测到 ateng-boot-redis?"}
        TwoLevelMgr["TwoLevelCacheManager\n(ateng-boot-redis 核心门面)"]
        StandaloneFallback["RedisPermissionCacheAdapter (5s LRU + L2)\nInMemoryPermissionCacheAdapter (10,000 有界 LinkedHashMap LRU)"]
        
        StpInt --> PermService
        PermService --> RedisModuleAdapter
        RedisModuleAdapter -->|存在 (推荐)| TwoLevelMgr
        RedisModuleAdapter -->|不存在| StandaloneFallback
    end

    subgraph ZeroTrust["3. 微服务 Same-Token 全闭环"]
        Outbound["SameTokenClientHttpRequestInterceptor\n(Spring 6 ClientHttpRequestInterceptor)"]
        RestClientAuto["RestClientCustomizer / RestTemplateCustomizer\n(自动注入请求头 SA-SAME-TOKEN)"]
        Inbound["@CheckSameToken + SaCheckSameTokenAspect\n(服务端零信任拦截验签)"]
        Outbound --> RestClientAuto
    end

    subgraph DualDefense["4. 登录防爆破双轨防卫"]
        DefenseService["LoginFailureLockService"]
        AccountLock["账号维度锁定 (5次/15m)"]
        IpLock["客户端 IP 频率熔断 (30次/30m, 带白名单)"]
        DefenseService --> AccountLock
        DefenseService --> IpLock
    end
```

### 1. 级联两级权限缓存与底座模块收敛复用 (L1+L2 Unified Cache)
- **底座收敛铁律 ([ADR-0021](./0021-core-baseline-convergence-and-zero-bloat-freeze-strategy.md))**：针对两级缓存，坚决杜绝在 `ateng-boot-satoken` 内部重复自研一套两级缓存与条带锁逻辑；
- **自适应适配器模式 (Adaptive Delegation)**：
  - `ateng-boot-satoken` 将 `ateng-boot-redis` 声明为可选依赖（`<optional>true</optional>`）；
  - 当 Spring 容器中存在 `TwoLevelCacheManager` 时，`PermissionCacheService` 100% 委派给 `ateng-boot-redis` 的 `TwoLevelCache`，全面复用其 64-条带并发锁（消除队头阻塞）、`ConcurrentLruMemoryCache`（或可选 Caffeine）、防雪崩 Jitter TTL、防穿透哨兵与 Redis Pub/Sub 广播驱逐；
  - 当未引入 `ateng-boot-redis` 时，若容器存在 `StringRedisTemplate` 且开启 Redis 则回退为 `RedisPermissionCacheAdapter`（L2 Redis + 本地 5 秒 `LinkedHashMap` 有界 LRU 微缓存），完全无 Redis 时则平滑回退至 `InMemoryPermissionCacheAdapter`（基于 `ReentrantLock` 与 `10,000` 上限 `LinkedHashMap` 的有界 LRU 内存缓存），保障纯单机与 H2 离线单测开箱即跑。

### 2. 多账号体系治理与 StpLogic 动态路由 (Multi-Account & StpLogic Routing)
- 在 `LoginHelper` 扩展多账号全量重载方法，显式支持 `loginType` 参数（如 `login(loginType, user)`、`logout(loginType)`、`getLoginUser(loginType)`、`isLogin(loginType)`、`getUserId(loginType)` 等）；
- **动态懒加载与预注册优先**：优先通过 `SaManager.getStpLogic(loginType, false)` 获取开发者预定义的 `StpLogic`；未预注册时自动按需实例化 `new StpLogic(loginType)` 并动态托管至 `SaManager`；
- **主账号类型四维上下文映射**：配置 `ateng.boot.satoken.primary-login-type`（默认 `"login"`），拦截器按优先级探测当前请求的活跃登录主体并自动映射至 `UserContextHolder` 与 `TenantContextHolder`，贯通 MyBatis-Plus 自动审计与行级租户隔离。

### 3. 微服务 Same-Token 零信任客户端拦截器全闭环 (Client Interceptor)
- 提供 `SameTokenClientHttpRequestInterceptor`（实现 Spring 6 原生 `ClientHttpRequestInterceptor`）；
- 条件装配 `RestClientCustomizer` 与 `RestTemplateCustomizer`，在 `ateng.boot.satoken.same-token.auto-inject-client = true` 时自动为受管 HTTP 客户端挂载请求头注入逻辑，与服务端 `@CheckSameToken` 切面构成完整的零信任内网防伪双向闭环；
- **与 `ateng-cloud` 体系的正交生态自洽**：微服务跨服务声明式 RPC（OpenFeign）由 `ateng-cloud-feign`（`FeignContextPropagationInterceptor` + `SaTokenSameTokenProvider`）负责四维上下文与 Same-Token 自动透传；高性能 RPC（Dubbo）由 `ateng-cloud-dubbo`（`DubboContextPropagationFilter` + `DubboSameTokenFilter`）负责透传与验签；WebFlux 响应式网关流量准入与集中签发由 `ateng-cloud-gateway` 承接。`ateng-boot-satoken` 恪守 ADR-0011 零臃肿底座原则，专注作为单体应用与业务微服务节点的安全底座，两者正交解耦、天然自洽。

### 4. 登录防暴力破解双轨防卫策略 (Dual-Track Login Defense)
- 升级 `LoginFailureLockService` 与 `LoginDefenseProperties`，在既有账号维度封禁的基础上，增加客户端 IP 维度防撞库熔断：
  - `ip-max-retry-count`（默认 30 次）：单 IP 在统计窗口内累计失败超过阈值立即熔断；
  - `ip-lock-time`（默认 30 分钟）：IP 锁定保护时间；
  - `ip-window-time`（默认 15 分钟）：失败计数滑动时间窗口；
  - `ip-white-list`（默认包含 `127.0.0.1`、`0:0:0:0:0:0:0:1`、`::1`）：内网与本地回环白名单豁免；
- **透明自动嗅探与显式兜底**：在未显式传入 IP 时，自动通过 `SaHolder.getRequest()` 从当前 HTTP 请求中智能提取客户端真实 IP（解析 `X-Forwarded-For`、`X-Real-IP`）；
- 触发 IP 锁定后直接 Fail-Fast 抛出 `BusinessException(CommonResultCode.FORBIDDEN, "客户端 IP 连续登录失败次数超限，已被临时锁定")`，转译为统一 HTTP 200 + code: 403 显式契约。

### 5. 在线会话与设备监控运维门面 (Online Session Governance Facade)
- 在 `LoginHelper` 封装高性能、增量游标检索门面：
  - `getOnlineUserCount()` / `getOnlineUserCount(loginType)`：快速统计指定或默认账号体系在线会话总数；
  - `searchOnlineUserPage([loginType], keyword, page, size)` / `searchOnlineUsers(...)`：游标增量扫描并批量反查组装 `OnlineUser` 模型与 `PageResult<OnlineUser>` 分页契约（含用户标识、账号、租户、账号体系、Token、登录设备、自动采集的 `loginIp`、登录时间与剩余有效时长）；
  - `kickoutByToken(tokenValue)` / `kickoutDevice(loginId, device)`：对称精准会话踢出门面。

### 6. 强类型配置双向桥接与活跃空闲超时 (Config Bridging & Active Timeout)
- 在 `SaTokenProperties` 声明 `TokenProperties` 强类型嵌套配置：
  - `timeout`（默认 `24h`，`Duration`）：绝对有效时间；
  - `activeTimeout`（默认 `30m`，`Duration`）：活跃空闲超时（支持滑动窗口无感续期，`<= 0` 表示关闭）；
  - `isConcurrent`（默认 `true`）：同一账号是否允许并发登录；
  - `isShare`（默认 `true`）：同一设备多次登录是否共享 Token；
  - `maxLoginCount`（默认 `12`）：单账号最大在线会话数；
  - `tokenStyle`（默认 `"uuid"`）：Token 生成算法风格；
- **防覆盖桥接时序与装配权衡（2026-11 #1176 缺陷演进）**：
  - 官方 `sa-token-spring-boot3-starter` 的 `SaBeanRegister.getSaTokenConfig()` 未标注 `@ConditionalOnMissingBean`，且 `SaBeanInject` 在构造函数中直接执行 `SaManager.setConfig(saTokenConfig)`。由于 `AtengSaTokenAutoConfiguration` 承载被 MVC/Filter 提前引用的 `@Bean`，仅加 `@AutoConfigureAfter` 无法阻止其 `@PostConstruct` 先于 `SaBeanInject` 执行并被默认 `new SaTokenConfig()`（`timeout=2592000s`、`activeTimeout=-1`、`isShare=false`）覆盖；而另行声明 `@Bean SaTokenConfig` 又会引发 `NoUniqueBeanDefinitionException`；
  - 最终决策：在 `@AutoConfigureAfter` 补齐 `SaBeanRegister`、`SaBeanInject` 与 `SaTokenContextRegister` 后，通过 `@Bean static BeanPostProcessor`（`saTokenConfigBeanPostProcessor`）在 `SaBeanRegister.getSaTokenConfig()` 完成 `@ConfigurationProperties("sa-token")` 绑定之后、`SaBeanInject` 调用 `SaManager.setConfig(...)` **之前**原地完成桥接，同时配合 `@PostConstruct` 与 `SmartInitializingSingleton.afterSingletonsInstantiated()` 对 `SaManager.getConfig()` 执行幂等收敛，兼顾完整 Spring Boot 容器与无 `SaBeanRegister` 的轻量单测上下文；仅定制挂载了独立 `SaTokenConfig` 的 `StpLogic` 不受全局桥接覆写；
- **字段级优先级与关闭语义对齐**：
  - 基于 Spring `Environment`（`Binder` 宽松绑定，兼容 `is-concurrent`/`concurrent`、`is-share`/`share`）逐字段判定配置来源：`显式配置 ateng.boot.satoken.token.* > 显式配置 sa-token.*（保留并反向回写 TokenProperties） > TokenProperties 默认生产基线`；
  - 针对 `sa-token-core` `StpLogic.isOpenCheckActiveTimeout()` 仅识别 `activeTimeout != -1L` 的特性，桥接写入 `SaTokenConfig` 时统一将 `activeTimeout <= 0`（如 `0s`、`-1s`）规范化为 `-1L`（反向回读为 `Duration.ofSeconds(-1)`），消除 `0s` 配置误触发立即冻结隐患。

### 7. 数据库驱动的动态 URL 路径权限匹配 SPI (DynamicPathPermissionProvider)
- 提供 `DynamicPathPermissionProvider` SPI 扩展点与 `PathPermissionRule` 规则模型（支持绑定可选 `loginType` 实施多账号路由权限隔离）；
- 拦截器 `AtengSaTokenInterceptor` 在路径白名单过滤后，自动回调已注入的 SPI 执行动态匹配（`AntPathMatcher` + HTTP Method）；未注册该 SPI 时完全短路跳过，保持零性能损耗。

### 8. 多账号权限缓存命名空间隔离、水平越权多账号感知与短效业务凭据门面 (2026-11 存量加固演进)
- **多账号两级权限缓存键向后兼容隔离**：
  - 修复多账号体系下相同 `userId` 或同名 `roleCode` 共享同一缓存键的串染隐患：当 `loginType` 为空或为默认 `"login"` 时保持 `ateng:satoken:cache:user-roles:{userId}` 键格式（100% 向后兼容）；当为非默认 `loginType`（如 `admin`、`member`）时，自动采用 `ateng:satoken:cache:{loginType}:user-roles:{userId}` 命名空间隔离；
  - `UserRolePermissionLoader` 补齐 `default loadPermissionsByRole(String roleCode, String loginType)`，`PermissionCacheService` 与 `SaTokenClusterBroadcaster` 补齐 `(String loginType, String userId/roleCode)` 精准刷新与跨节点广播驱逐重载；
- **水平越权防御 (`@CheckDataOwner`) 多账号自洽**：
  - `@CheckDataOwner` 新增 `type()` 属性（默认 `""`），切面 `SaCheckDataOwnerAspect` 优先按显式 `type` 路由，未指定时自动通过 `LoginHelper.getActiveLoginType()` 嗅探当前请求活跃账号体系，并向 `DataOwnerProvider.getOwnerId(target, loginType)` 透传；
- **`LoginHelper` 对称续签与 `OnlineUser` 真实 IP 自动采集**：
  - `LoginHelper` 补齐 `registerStpLogic(loginType, stpLogic)` 与 `renewTimeout` / `renewTimeoutByToken` 强类型 `Duration` 续期门面；在 `login(...)` 签发会话时自动嗅探 HTTP 请求客户端真实 IP 写入 `SaLoginParameter` 扩展字段，并在 `OnlineUser` 补齐 `tenantId` 与 `loginType` 元数据；
- **短效临时令牌门面 (`TempTokenHelper`)**：
  - 基于 `SaTempUtil` 与 Core `JsonUtil` 封装零 `@class` 污染的 `TempTokenHelper`（并在 `LoginHelper` 提供委托入口），提供 `createTempToken`、`parseTempToken`、`getTempTokenTimeout`、`deleteTempToken` 对称 API，覆盖大文件私有下载短链、一次性回调 Ticket 与限时业务凭据场景；
- **JDK 21+ 虚拟线程防 Pinning 清零**：
  - 将 `LoginHelper`、`PermissionCacheService`、`AtengSaTokenInterceptor`、`AnonymousUrlProcessor` 中的 `synchronized` 全面替换为 `ReentrantLock`（遵循 `isHeldByCurrentThread()` 校验释放范式，对齐 [ADR-0029](./0029-virtual-threads-and-adaptive-async-runtime.md)）。

### 9. 全局 `check-login=true` 多账号路由感知与上下文对齐 (2026-11 缺陷修复演进)
- **问题背景（Issue #1034）**：既有 `AtengSaTokenAutoConfiguration` 与 `SaTokenWebMvcConfigurer` 在 `ateng.boot.satoken.interceptor.check-login=true` 时两处重复硬编码 `StpUtil.checkLogin()`（且父类 `SaInterceptor.auth` 默认非空导致判空失效），致使 `LoginHelper.login("admin", user)` 等非默认 `loginType` 多账号接口被错误拦截返回 401；
- **路由级 `loginType` 映射 (`interceptor.login-type-routes`)**：在 `SaTokenProperties.InterceptorProperties` 新增 `Map<String, List<String>> loginTypeRoutes`（`LinkedHashMap` 保序，Key 为 `loginType`，Value 为 AntPath 模式列表，规避 Spring Boot `ConfigurationPropertyName` 对路径 Key 中 `/`、`*` 字符的裁剪问题），匹配时采用 `AntPathMatcher.getPatternComparator(lookupPath)` 最精确模式优先、同精度按声明顺序兜底；
- **单一真相源与统一优先级解析**：移除 `AtengSaTokenAutoConfiguration` 与 `SaTokenWebMvcConfigurer` 中的重复 `StpUtil.checkLogin()` 挂载，将 `check-login` 逻辑统一收敛至 `AtengSaTokenInterceptor`，并追踪 `hasCustomAuth` 标记兼容外部自定义 `setAuth(...)`。非白名单请求的校验目标 `loginType` 按以下确定性优先级解析：`外部自定义 auth > DynamicPathPermissionProvider 动态规则 loginType > interceptor.login-type-routes > ateng.boot.satoken.primary-login-type（默认 "login"）`；
- **严格校验与正交开关**：`check-login=false` 时 `login-type-routes` 不强制触发 401 拦截，仅作为动态规则与上下文同步的 `loginType` 路由提示；`check-login=true` 未命中任何映射时严格校验 `primary-login-type`，严禁“任意账号体系已登录即放行”的越权降级；
- **四维上下文对齐**：`syncContextIfLoggedIn` 优先绑定当前请求路由鉴权命中的 `loginType`，避免同一请求携带多端 Token 时 `UserContextHolder` 与 `TenantContextHolder` 串号。

### 10. 请求链路超管收敛、多账号注解路由、受控租户切换与多租户键值隔离闭环 (2026-11 演进)
- **超管判定收敛与数据权限弱依赖桥接 (#1077)**：
  - `SaTokenProperties` 新增 `superAdminRoles`（默认 `["admin", "super_admin"]` 向后兼容）与 `superAdminPermissions`（默认 `["*", "*:*:*"]`，显式置空列表 `[]` 禁用通配符判定）并同步至 `LoginHelper`，彻底解决普通租户管理员持有 `"admin"` 角色被误判为全平台超管的越权隐患；
  - 新增 `DataPermissionBridge` SPI 与类路径隔离的 `MyBatisPlusDataPermissionBridge`，在 `preHandle` 先重置 `setSuperAdmin(false)` 再注入 `LoginHelper.isSuperAdmin(loginType)`，并在 `afterCompletion` 与异常中止分支确定性 `clear()`，严守 `ateng-boot-satoken` 对 `ateng-boot-mybatis-plus` 零强制依赖。
- **Sa-Token 注解多账号自动路由 (#1076)**：
  - 新增 `interceptor.auto-route-annotation-type`（默认 `true`）与 `SaTokenRouteContextHolder`，`AtengSaTokenInterceptor.preHandle`（含 `isWhitelisted` 白名单分支）写入命中路由 `loginType`；
  - `SaTokenAnnotationAutoRouteRegistrar` 替换 `SaAnnotationStrategy.instance` 中 5 个注解处理器（天然覆盖 `@SaCheckOr`），未显式声明 `type()` 时按 `路由命中 loginType > primary-login-type > "login"` 解析并通过 `LoginHelper.getStpLogic(resolvedType)` 懒加载；`SaCheckDataOwnerAspect` 同步优先感知 `SaTokenRouteContextHolder`。
- **白名单闭环与 `X-Tenant-Id` 受控切换 (#1078)**：
  - 移除 `SaTokenWebMvcConfigurer` 的 `.excludePathPatterns(finalExcludes)`，让全部静态/声明白名单统一经过 `AtengSaTokenInterceptor.isWhitelisted` 短路放行并纳入 `afterCompletion` 清理闭环；
  - 新增 `tenant.*` 配置（默认关闭匿名解析与超管切换）与 `TenantHeaderSwitchValidator` SPI；普通已登录用户与白名单带有效 Token（含跨端活跃会话）请求绝对忽略 `X-Tenant-Id` 防绕过，超管与匿名合法开关下携带非法格式或未通过 SPI 校验均 Fail-Fast 抛出 `BusinessException`。
- **YAML 静态路径权限与共享路径多账号探测 (#1095)**：
  - 严守 `ateng-boot-web` 对 `ateng-boot-satoken` 零反向依赖；在 `InterceptorProperties` 新增 YAML `path-permissions`（复用 `PathPermissionRule`，作为 `DynamicPathPermissionProvider` 未命中时的静态回退）与 `shared-login-type-paths`（允许共享公共端点在 `check-login=true` 下按请求实际活跃体系探测放行，显式路由维持严格隔离）。
- **多租户防爆破隔离键与角色权限缓存键隔离 (#1079 / #1093)**：
  - `LoginFailureLockService` 全量补齐 9 组 `(tenantId, loginType, account, ...)` 对称重载，采用十进制长度前缀定界编码 `scoped:{len}:{tenantId}|{len}:{loginType}|{len}:{account}` 防止冒号/空串键碰撞；
  - `PermissionCacheProperties` 新增 `tenantScoped`（默认 `false`），开启时严格聚焦隔离 `role-perms` 缓存键（`tenantId` 优先取会话所属租户 `LoginHelper.getTenantId(loginType)` 防超管切租户串缓存，回退 `TenantContextHolder.getTenantId()`），同步扩展 `UserRolePermissionLoader` 三参方法与 `SaTokenClusterMessage.tenantId` 集群精准驱逐。

### 11. 复合 `loginId` 鉴权缓存闭环、切租户回源隔离与在线会话多租户防越权治理 (#1164 / #1166)
- **复合 `loginId` 统合拆解与缓存键严格对称 (#1164)**：
  - `LoginHelper` 暴露公开不可变 `ParsedLoginId(tenantId, userId, rawLoginId)` 与 `parseCompositeLoginId(Object loginId)`；解析时优先借助活跃会话中 `LoginUser.getTenantId()` 精确匹配 `{tenantId}_` 前缀，彻底消除 `tenantId`（如 `tenant_B`）与 `userId`（如 `operator_88001`）双侧均含下划线 `"_"` 的切分二义性，无会话时按首个 `"_"` 兼容切分；
  - `CachedStpInterface` 剥离租户前缀向 `UserRolePermissionLoader`（新增带 `tenantId` 的 `default` 三参 `loadRolesByUserId` / `loadDirectPermissionsByUserId` 重载，并强化 `DefaultUserRolePermissionLoader` / `DefaultStpInterface` 的复合 `SaSession` 回退解析）传入真实业务 `userId`；用户角色与直属权限缓存键向 `role-perms:<tenantId>:<roleCode>` 对齐为 `ateng:satoken:cache:[<loginType>:]user-roles:[<tenantId>:]<userId>` 与 `user-perms`；`PermissionCacheService` 补齐 `(loginType, tenantId, userId)` 清理/刷新重载（旧无 `tenantId` 签名自动兼容性清理解除漏清隐患），`SaTokenClusterSyncListener` 同步提取并广播 `tenantId`。
- **切租户视角下鉴权 DB 回源的归属租户栈隔离 (#1164)**：
  - 当超管通过 `AtengSaTokenInterceptor` 将 `TenantContextHolder` 切至工作租户 B，触发 `@SaCheckPermission` 二级缓存未命中回调 `UserRolePermissionLoader` 查库时，`CachedStpInterface` 通过 `TenantContextHolder.runWithTenant(ownerTenantId, ...)` 压栈临时切换至权限主体归属租户 A，执行完毕于 `finally` 精准恢复工作租户 B（不干扰外层 `ignoreDepth`），从根本上杜绝 MyBatis-Plus 行级多租户过滤导致的跨租户鉴权串味。
- **在线会话条件分页与租户防越权强退 (#1166)**：
  - 新增 `OnlineUserQuery`（支持 `tenantId` 精确筛选、`keyword` 综合匹配、`userId`/`userAccount`/`userName`/`loginDevice` 细化、结合 Core `CidrMatcher` 支持精确与网段匹配的 `loginIp` 过滤及 `maxScanLimit` 覆盖）与 `SaTokenProperties.OnlineSessionProperties`（`online-session.max-scan-limit`，默认 `2000`，替代硬编码 `500` 截断）；
  - 新增 `getOnlineUserByToken`、`getTokenTenantId`、`isTokenBelongsToTenant` 与带租户归属守护的 `kickoutByTokenForTenant` / `logoutByTokenValueForTenant`：校验令牌归属与 `expectedTenantId` 不一致（含会话缺租户属性）时 Fail-Fast 抛出 `BusinessException(403)` 阻断越权踢人，令牌已过期则幂等返回 `false`。

---

## 后果与权衡 (Consequences & Trade-offs)

### 正向收益
- **微秒级鉴权响应**：真 L1 本地微缓存削减 90%+ 的 Redis 网络交互，鉴权吞吐量提升数倍；
- **多端业务完全解耦**：管理端（`admin`）与前台移动端（`member`）可在单一应用内无缝独立认证并共享审计底座；
- **微服务内部调用零心智负担**：客户端自动透传 Same-Token，服务端自动验签，彻底消除开发疏漏；
- **全方位防撞库防护**：账号锁与 IP 熔断双轨并行，阻断暴力破解与账号批量锁死（DoS）攻击；
- **零额外包膨胀与高复用**：两级缓存复用 `ateng-boot-redis`，保持 Starter 极度轻量与向后兼容。

### 妥协与成本
- 当使用多账号体系时，若两个不同端在同一 HTTP 请求中均携带有效 Token，框架将依据 `primary-login-type` 配置的优先级选择当前请求的主操作人上下文。
