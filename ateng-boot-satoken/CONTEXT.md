# Sa-Token Context

`ateng-boot-satoken` 是面向企业级分布式应用与微服务架构的高性能、生产就绪型安全鉴权与分布式会话治理 Starter，基于 Sa-Token `1.46.0` 深度自适应构建，坚持显式契约、零 `@class` 冗余、双轨拦截防御、两级权限缓存、水平越权防御、声明式动态白名单、双维身份模拟、集群即时同步与登录防暴力破解。

## Language

**AtengSaTokenInterceptor**:
企业级双轨路由拦截器，深度承接 Sa-Token 路由匹配与白名单过滤，更关键地负责全链路双维上下文的生命周期镜像管理：在 `preHandle` 将认证上下文无感对称注入 `UserContextHolder` 与 `TenantContextHolder`，在 `afterCompletion` 强制执行双清空，从源头杜绝 Web 容器工作线程池复用造成的跨请求上下文串染与数据越权。
_Avoid_: SaRouteInterceptor, SecurityFilter, AuthInterceptor

**LoginHelper**:
基于 Sa-Token 强类型扩展的高级登录与上下文门面工具类，提供开箱即用的登录会话构建、多账号体系解耦（多 `loginType` 重载路由）、多终端会话隔离、强类型会话载荷读写（`LoginUser`）、二级安全认证治理（`openSafe`/`checkSafe`/`closeSafe`）、数据所有权校验断言（`checkSelf`/`checkBelongsToTenant`）、身份模拟（`switchTo`/`runAs`）与上下文清理。内置对多租户复合主键模式（`{tenantId}_{userId}`）的双向对称解析与剥离，确保 `getUserId()` 始终返回纯净的业务主键。彻底杜绝将未类型化的裸 Map 或易失性散落数据写入底层 Session。
_Avoid_: AuthHelper, SessionManager, SecurityUtils, TokenHelper

**LoginUser**:
企业级强类型会话载荷标准模型，封装 `userId`、`userAccount`、`userName`、`userType`、`deptId`、`tenantId`、`roles`（角色编码集）、`permissions`（细粒度权限码集）及安全扩展字典 `extraData`。在会话持久化与网络传输中始终保持规范自洽。
_Avoid_: UserSession, PrincipalVO, AuthUser, SessionUser

**SaTokenRedisDao**:
基于 Spring `StringRedisTemplate` 的生产级分布式会话与持久层实现，采用纯净 JSON 序列化存储，彻底消除传统序列化方式携带的脆弱 `@class` 类元数据，杜绝反序列化漏洞与跨模块耦合；键模式扫描检索（`searchData`）全面基于增量游标 `SCAN` 驱动，杜绝 `KEYS *` 阻塞 Redis 单线程。
_Avoid_: RedisDao, SaRedisTemplate, RedisSessionStore

**CachedStpInterface**:
高性能两级角色与权限数据源切片适配器，构建 `[用户ID -> 角色编码集]` 与 `[角色编码 -> 权限码集]` 两级缓存切片，大幅降低重复权限计算开销；内置 `__EMPTY__` 短 TTL 哨兵防穿透机制与 10%~20% 动态 Jitter 随机抖动防雪崩机制，杜绝鉴权对底层数据库的周期性风暴冲击。
_Avoid_: StpInterfaceImpl, PermissionDataSource, SaPermissionProvider

**PermissionCacheService**:
高性能两级级联权限与角色即时热刷新治理服务，构建 L1（JVM 进程内微缓存，带防 OOM 边界与极短 TTL）+ L2（Redis 分布式缓存）级联架构。提供面向用户维度（`refreshUserPermissions`）、角色维度（`refreshRolePermissions`）及全域游标增量清空（`clearAll`）的极简门面；本地更新即时淘汰 L1+L2，并通过 `SaTokenClusterBroadcaster` 触发 Redis Pub/Sub 全集群广播以秒级驱逐所有异地节点的 L1 微缓存。
_Avoid_: CacheCleaner, PermissionCacheManager, RefreshService

**@CheckDataOwner**:
水平越权（BOLA / IDOR）声明式防卫注解，配合 AOP 切面 `SaCheckDataOwnerAspect`。支持通过 SpEL 表达式提取业务入参目标用户/实体归属标识，并支持挂载 `DataOwnerProvider` SPI 进行数据所有权反查校验，内置超级管理员自动豁免机制。未通过时快速失败阻断并返回统一 HTTP 200 + 403 业务响应。
_Avoid_: @DataOwner, @CheckOwner, @VerifyOwner, @ResourceOwner

**DataOwnerProvider**:
数据所有权校验与反查泛型 SPI 接口（`DataOwnerProvider<T>`），当入参为复杂实体主键（如 `orderId`）或非当前用户直接主键时，允许业务层注入自定义校验逻辑反查数据实际归属人；内置提供 `DefaultDataOwnerProvider` 实施直等断言。
_Avoid_: DataOwnerChecker, OwnerService, OwnerResolver, OwnerVerifier

**@Anonymous**:
声明式公开访问与动态白名单注解，可标注于 Controller 方法或类级别（类级别支持局部方法排除）。由 `AnonymousUrlProcessor` 在应用就绪时自动扫描 Spring MVC 端点并自适应转译 AntPath（如 `{id}` 转译为 `*`），无感汇聚至受管拦截器白名单，免除静态 YAML 硬编码配置。
_Avoid_: @Public, @PermitAll, @IgnoreAuth, @NoLogin, @Uncheck

**SaTokenWhitelistProvider**:
动态白名单提供者 SPI 接口，支持业务模块、插件 Starter 或配置中心在运行时动态向安全拦截器注册免鉴权 URL 路径集合，实现松耦合的白名单扩展机制。
_Avoid_: DynamicWhitelist, WhitelistService, WhitelistProvider, PathFilterSPI

**DynamicPathPermissionProvider**:
数据库驱动的动态 URL 路径权限匹配 SPI 接口，支持业务层从数据库或配置中心动态加载“HTTP Method + AntPath 路径 -> 权限码”映射规则，并在 `AtengSaTokenInterceptor` 中自动执行动态匹配校验，免去在 Controller 方法硬编码注解。
_Avoid_: PathPermissionChecker, UrlAuthService, DynamicRbacSPI

**OnlineUser**:
企业级在线会话聚合模型，封装在线用户 `userId`、`userAccount`、`userName`、`tokenValue`、`loginDevice`、`loginIp`、`loginTime` 及当前 Token 剩余有效时间（`tokenTimeout`），专供管理后台在线用户列表监控与强退治理。
_Avoid_: OnlineSessionVO, ActiveUser, SessionDetail

**RunAs / Impersonate**:
双维身份模拟机制，区分跨请求会话级持久模拟（`LoginHelper.switchTo` / `endSwitch`）与代码块瞬态安全模拟（`LoginHelper.runAs`）。内部自动固化记录最初源头 `originalUserId`，确保多次连续切换源头不漂移；瞬态模式采用 ThreadLocal 栈式保护并在 `finally` 块确定性恢复；业务层 `LoginHelper.getUserId()` 自动对齐被模拟人，同时向 Core/Web `@OperateLog` 切面提供双维身份（真实运维人与被模拟人）审计穿透。
_Avoid_: SwitchUser, MockUser, FakeLogin, ImpersonationHelper

**SaTokenClusterBroadcaster**:
基于 Redis Pub/Sub 的分布式集群即时同步广播器。每个节点启动时通过 `fastSimpleUUID()` 生成不可变节点实例标识 `instanceId`；在权限热刷新或安全注销事件触发时，向指定 Topic 广播强类型 `SaTokenClusterMessage`，无 Redis 时自动安全静默降级。
_Avoid_: ClusterSyncService, RedisBroadcaster, SecurityClusterSender, ClusterEventBus

**SaTokenClusterSyncListener**:
集群安全同步监听器与自消费防卫守卫（Self-Consumption Guard）。监听 Spring 原生安全事件自动触发集群广播；接收异地节点广播时比对 `instanceId`，若为本机广播则直接静默丢弃，杜绝二次清理风暴与广播回环；异地广播则秒级精准失效本地两级权限缓存。
_Avoid_: ClusterReceiver, SecurityClusterConsumer, SyncHandler

**LoginFailureLockService**:
登录防暴力破解策略服务，支持“账号锁定 + IP 限流熔断”双轨防卫。提供失败原子累计（`recordFailure`）、成功对称清零（`clearFailure`）、剩余次数与锁定状态查询及 `checkLocked` Fail-Fast 防卫。采用 Redis（带滑动窗口 TTL）与进程内线程安全内存容器（带时间戳惰性淘汰与容量超限淘汰）双模自适应存储，连续输错达到上限自动联动 `LoginHelper.disable` 接入框架原生封禁底座。
_Avoid_: LoginDefenseService, BruteForceLocker, LoginAttemptService, FailureCounter

**SameTokenClientHttpRequestInterceptor**:
微服务内部零信任调用客户端请求拦截器（基于 Spring 6 `ClientHttpRequestInterceptor`）。在服务发起跨服务 HTTP 调用时，自动从底层 `SaSameUtil.getToken()` 提取当前微服务内网防伪凭据并注入请求头，与服务端 `@CheckSameToken` 切面构成全闭环防伪壁垒。
_Avoid_: SameTokenFilter, FeignSameTokenInterceptor, InternalTokenInjector

**SaTokenEventPublisher**:
Sa-Token 全生命周期底层监听器与 Spring 原生 `ApplicationEvent` 总线桥接发布器，将登录、注销、被踢、被顶、封禁、解封及二级认证开启/关闭等动作发布为标准 Spring 事件（`SaLoginEvent`、`SaKickoutEvent` 等），支持业务通过 `@EventListener` 零侵入异步解耦消费。
_Avoid_: SecurityEventListener, SaEventListener, EventBridge

**SaTokenOpenApiCustomizer**:
基于 `@ConditionalOnClass(GlobalOpenApiCustomizer.class)` 条件装配的 OpenAPI / Knife4j 鉴权定制器，自动在 Swagger / Knife4j 文档中挂载 `satoken` APIKey 鉴权方案与全局 `SecurityRequirement`，一键激活文档右上角 Authorize 锁形鉴权弹窗。
_Avoid_: SwaggerSecurityCustomizer, Knife4jAuthInjector, OpenApiDocCustomizer

**@CheckSameToken**:
微服务内部跨服务安全调用零信任校验注解，配合 `SaCheckSameTokenAspect` AOP 切面与 `SaSameUtil.checkCurrentRequestToken()`，强制校验微服务调用链携带的内部 Same-Token 防伪签名，构筑零信任微服务内网安全壁垒。
_Avoid_: @InternalOnly, @RequireSameToken, @VerifyServiceToken

**SaTokenCurrentUserProvider**:
对接 `ateng-boot-core` 的 `CurrentUserProvider` 与 `CurrentTenantProvider` SPI 适配机制，在 `AtengSaTokenAutoConfiguration` 初始化时自动完成 `UserContextHolder.setUserProvider(LoginHelper::getUserId)` 与 `TenantContextHolder.setTenantProvider(LoginHelper::getTenantId)` 双向挂载，无缝透传至 MyBatis-Plus 审计自动填充引擎（自动写入 `createBy`、`updateBy`）与行级多租户隔离上下文（`TenantContextHolder`）。
_Avoid_: UserAuditorProvider, SecurityContextAdapter

**Zero-Trust Same-Token Defense**:
微服务内网零信任防穿透壁垒。在服务端通过 `@CheckSameToken` 切面强制校验内部受信签名，阻断绕过网关直连内部微服务暴露端口的非法探测；原生客户端通过 `SameTokenClientHttpRequestInterceptor` 自动注入出站标头；微服务跨服务 RPC 治理（Feign / Dubbo）与响应式网关（WebFlux Gateway）统一由 `ateng-cloud` 套件正交承接。
_Avoid_: 内部令牌, 网关白名单, 服务鉴权头

## Dependencies

- **ateng-boot-core**: 核心基础异常体系、`CommonResultCode`、`Result<T>` 显式契约、`JsonUtil` 序列化门面、`UserContextHolder` 与 `TenantContextHolder` 双维上下文 SPI 及 `DesensitizeUtil` 基础工具
- **ateng-boot-web** (Optional): 驱动声明式 `@Anonymous` 动态端点提取与转译、`@CheckDataOwner` AOP 切面及 `@OperateLog` 双维操作日志穿透
- **cn.dev33:sa-token-spring-boot3-starter**: Sa-Token 官方 Spring Boot 3 核心鉴权依赖
- **cn.dev33:sa-token-spring-aop**: Sa-Token 官方 Spring AOP 穿透切面（覆盖 Controller 与 Service 业务层注解鉴权）
- **org.springframework.data:spring-data-redis** (Optional): 驱动 `SaTokenRedisDao`、分布式权限缓存、`SaTokenClusterBroadcaster` 集群广播与 `LoginFailureLockService` 分布式防爆破计数
- **io.github.atengk:ateng-boot-mybatis-plus** (Optional): 驱动自动审计填充与多租户上下文生命周期自动对齐
- **org.springdoc:springdoc-openapi-starter-webmvc-ui** (Optional): 驱动 Knife4j / OpenAPI 自动鉴权弹窗

## Ecosystem Collaboration (ateng-cloud)

- **ateng-cloud-gateway**: 承载响应式 WebFlux API 网关公网流量准入、Header 清洗、统一鉴权与零信任 Same-Token 签发。
- **ateng-cloud-feign**: 承载声明式 HTTP RPC 跨服务调用，通过 `FeignContextPropagationInterceptor` 自动透传四维上下文，并通过 `SaTokenSameTokenProvider` 桥接 Sa-Token 自动注入 `SA-SAME-TOKEN`。
- **ateng-cloud-dubbo**: 承载高性能 RPC 跨服务调用，通过 `DubboContextPropagationFilter` 透传四维上下文，并通过 `DubboSameTokenFilter` 自动探测 Sa-Token 注入与校验 `SA-SAME-TOKEN`。
