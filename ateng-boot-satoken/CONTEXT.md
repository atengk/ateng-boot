# Sa-Token Context

`ateng-boot-satoken` 是面向企业级分布式应用与微服务架构的高性能、生产就绪型安全鉴权与分布式会话治理 Starter，基于 Sa-Token `1.46.0` 深度自适应构建，坚持显式契约、零 `@class` 冗余、双轨拦截防御、两级权限缓存、原生事件广播与零信任服务间安全调用。

## Language

**LoginHelper**:
基于 Sa-Token 强类型扩展的高级登录与上下文门面工具类，提供开箱即用的登录会话构建、多终端会话隔离、强类型会话载荷读写（`LoginUser`）、二级安全认证治理（`openSafe`/`checkSafe`/`closeSafe`）与上下文清理。彻底杜绝将未类型化的裸 Map 或易失性散落数据写入底层 Session。
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
两级权限与角色即时热刷新治理服务，提供面向用户维度（`refreshUserPermissions`）、角色维度（`refreshRolePermissions`）及全域游标增量清空（`clearAll`）的极简门面，自适应支持 Redis 分布式模式与 JVM 进程内静态内存降级模式。
_Avoid_: CacheCleaner, PermissionCacheManager, RefreshService

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

## Dependencies

- **ateng-boot-core**: 核心基础异常体系、`CommonResultCode`、`Result<T>` 显式契约、`JsonUtil` 序列化门面、`UserContextHolder` 与 `TenantContextHolder` 双维上下文 SPI 及 `DesensitizeUtil` 基础工具
- **cn.dev33:sa-token-spring-boot3-starter**: Sa-Token 官方 Spring Boot 3 核心鉴权依赖
- **cn.dev33:sa-token-spring-aop**: Sa-Token 官方 Spring AOP 穿透切面（覆盖 Controller 与 Service 业务层注解鉴权）
- **org.springframework.data:spring-data-redis** (Optional): 驱动 `SaTokenRedisDao` 与分布式权限缓存
- **io.github.atengk:ateng-boot-mybatis-plus** (Optional): 驱动自动审计填充与多租户上下文生命周期自动对齐
- **org.springdoc:springdoc-openapi-starter-webmvc-ui** (Optional): 驱动 Knife4j / OpenAPI 自动鉴权弹窗
