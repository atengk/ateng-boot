# Sa-Token 安全鉴权与会话管控 (ateng-boot-satoken)

面向企业级应用的高性能轻量鉴权与分布式会话治理底座，提供细粒度权限控制、双轨拦截防御、多端会话互斥与全链路审计协同。

## Language

**LoginId**:
用户登录凭据与全局唯一身份主键，标准化为字符串并在全链路上下文作为不可变操作人锚点。
_Avoid_: UserId, AccountKey

**LoginUser**:
登录主体强类型业务实体，包含操作人基本资料、角色集、权限码集与租户元数据。
_Avoid_: UserVO, SessionUser, AuthInfo

**SecurityContext**:
请求生命周期内的安全运行时上下文，与 CurrentUserProvider SPI 桥接透传。
_Avoid_: AuthContext, UserThreadLocal

**DeviceType**:
端设备类型，用于实施同端互斥顶人下线或多端并发共存。
_Avoid_: ClientType, Platform

**SaTokenDao**:
会话与凭证持久层适配器，自适应屏蔽单机安全内存与分布式 Redis 差异。
_Avoid_: StorageAdapter, SessionDao

**PermissionCache**:
用户角色与权限的两级缓存映射，提供双向解耦与热点更新清理支持。
_Avoid_: AuthCache, JurCache

**SameToken**:
服务间内部调用的防伪造与防重放瞬态数字凭据，支撑分布式内部零信任通信。
_Avoid_: InternalToken, SecretKey

**TenantScopedLoginId**:
多租户环境下防止数字主键冲突的复合登录凭据，格式为 `{tenantId}_{userId}`。
_Avoid_: CompositeUserId, TenantUserKey

