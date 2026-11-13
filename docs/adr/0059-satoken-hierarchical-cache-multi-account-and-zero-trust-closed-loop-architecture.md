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
  - `activeTimeout`（默认 `30m`，`Duration`）：活跃空闲超时（支持滑动窗口无感续期）；
  - `isConcurrent`（默认 `true`）：同一账号是否允许并发登录；
  - `isShare`（默认 `true`）：同一设备多次登录是否共享 Token；
  - `maxLoginCount`（默认 `12`）：单账号最大在线会话数；
- 启动时自动将这些 `Duration` 桥接写入 Sa-Token 全局配置 `SaManager.getConfig()`，消除配置割裂与单位歧义。

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
