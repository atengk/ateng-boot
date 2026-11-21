# Social Context

`ateng-boot-social` 是面向企业级分布式应用与微服务架构的高性能、生产就绪型第三方社会化登录与 OAuth2 基础设施 Starter，基于 Dromara Anduin 2.3.0（JustAuth 现代演进版）协议引擎深度自适应构建，坚持显式契约、零臃肿依赖、自定义平台扩展（`AuthSource`）、OAuth 2.1 PKCE 状态解耦与阅后即焚、微信快照页虚拟号刚性防卫、多端设备治理、动态高熵 State 防伪状态机、阅后即焚 BindTicket 两阶段绑定、跨号抢占冲突防卫、开放重定向白名单校验、孤儿账号死号防卫与 Spring 领域事件驱动。

## Language

**SocialAuthTemplate**:
核心门面模板类，提供生成第三方授权地址（`getAuthorizeUrl`）、标准 Web 回调换码认证（`login`，含 PKCE `code_verifier` 阅后即焚与快照虚拟号防卫）、移动端/小程序凭据直传登录（`directLogin`）、两阶段消费票据绑定（`bind`）、个人中心已登录直接绑定（`bindDirect`）、解除绑定（`unbind`）、第三方令牌续期（`refreshToken`）、授权吊销（`revokeAuth`）、已绑定列表查询（`listBoundAccounts`）及应用配置提取（`getAppConfig`）的全生命周期对称 API。
_Avoid_: OAuthService, SocialUtil, SocialManager, ThirdPartyLoginHelper

**SocialAppFactory**:
多应用实例构建与生命周期管理工厂，支持动静双轨配置解析、自定义 `AuthSource` 容器自动发现与运行期对称注册/注销（`registerExtendSource` / `unregisterExtendSource` / `getExtendSourceNames`）、上游内置枚举映射缺陷自愈（`AFDIAN` / `QQ_MINI_PROGRAM`），以及多租户动态代理/超时指纹比对热更新与专有 `RestClient` 对称驱逐（`unregisterAppRestClient` / `clearAppRestClients`）。
_Avoid_: AuthRequestFactory, SocialClientBuilder, OAuthAppRegistry

**SocialUser**:
标准化第三方用户画像领域模型，统一收敛 `openId`、`unionId`、`source`、`uuid`、`username`、`nickname`、`avatar`、`email`、`snapshotUser`（微信快照虚拟号标识）等核心身份字段，并内聚原始 `rawToken` 与 `rawUserInfo` 快照。
_Avoid_: AuthUser, ThirdPartyUser, OAuthUserInfo, OpenUser

**SocialToken**:
第三方平台访问令牌强类型标准模型，封装 `accessToken`、`refreshToken`、`expireIn`、`tokenType`、`scope`、`openId`、`unionId` 与 OIDC `idToken`，支持令牌续期与吊销。
_Avoid_: OAuthToken, AccessTokenVO, ThirdPartyToken

**SocialBoundAccount**:
已绑定第三方社交账号标准化视图模型，面向个人中心与账号设置场景，承载 `appKey`、`source`、`openId`、`unionId`、`username`、`nickname`、`avatar` 及绑定时间戳 `boundAt`。
_Avoid_: BoundUserVO, SocialBindingDTO, ThirdPartyAccountVO

**StateContext**:
防伪状态码上下文载荷模型，结构化绑定 `tenantId`、`redirectUrl`、`createdAt` 与动态扩展参数，用于抵御 CSRF 攻击、开放重定向钓鱼与会话劫持。
_Avoid_: StatePayload, OAuthStateData, StateMeta

**SocialLoginOptions**:
社交登录控制参数模型，支持显式传递 `deviceType`（多端设备治理）、`loginType`（独立账号体系隔离）、`timeout`（自定义会话超时）与扩展会话参数。
_Avoid_: LoginParam, SessionConfig, AuthOptions

**SocialAuthResult**:
统一社交认证强类型结果模型，封装 `state` 状态枚举（`LOGGED_IN` 成功登入 / `REQUIRE_BIND` 待绑定）、`userId`、`socialUser`、`bindTicket`（两阶段绑定临时票据）、`stateContext` 与 `tokenInfo`（会话凭据）。
_Avoid_: AuthResponse, LoginResultVO, SocialLoginVO

**SocialUserDetailsService**:
第三方身份与本地用户映射核心业务 SPI，由宿主系统实现。负责通过 `SocialUser` 反查本地用户主键（支持微信三阶 unionId/openId 识别）、首次登录静默自动开户（`autoRegisterUser`）、孤儿账号解绑前置安全检查（`canUnbind`）、绑定关系落库（`bindSocialUser`）、解除绑定（`unbind`）及已绑定账号列表查询（`listBoundAccounts`）。
_Avoid_: SocialAccountService, UserMappingSPI, SocialMemberProvider

**SocialSessionManager**:
社交登录受管会话凭据签发 SPI，默认装配 `SaTokenSocialSessionManager` 联动 `LoginHelper` 自动签发强类型 Token-Session 与多端多设备管控；支持宿主定制专有 JWT/双 Token 体系。
_Avoid_: TokenService, SessionIssuer, SaTokenBridge

**SocialAuthStateCache**:
高熵防伪 State 状态机缓存与校验 SPI，遵循读后即焚原子消费语义。默认在单机环境下自动装配 `InMemorySocialAuthStateCache`（基于 Caffeine 本地有界缓存），在 Redis 环境下装配 `RedisSocialAuthStateCache`。
_Avoid_: SocialStateRepository, StateCache, OAuthStateStore, StateService

**BindTicketStore**:
两阶段绑定临时票据存储 SPI，管理带短时 TTL（默认 5 分钟）的高熵 `BindTicket`，提供原子消费即焚（`remove`）机制，杜绝二次重放绑定。默认提供 `InMemoryBindTicketStore` 与 `RedisBindTicketStore` 双模实现。
_Avoid_: TicketCache, BindTokenStore, BindSessionDao

**SocialAppConfigProvider**:
动态第三方应用配置源扩展 SPI，支持从数据库、配置中心或多租户元数据中动态装配 `SocialAppConfig`，打破静态 YAML 配置文件硬编码限制。
_Avoid_: DynamicSocialAppProvider, DynamicOAuthProvider, TenantSocialProvider

**SocialAppConfig / SocialAppItemProperties**:
社交平台应用实例领域配置与 YAML 属性映射载荷，封装 `appKey`、`source`、`clientId`、`clientSecret`、`redirectUri`、`scopes`、`ignoreCheckState`、`autoSignUp`、OAuth 2.1 `pkce`、微信快照防卫 `rejectSnapshotUser` 及企业级专有配置（微软 Entra `microsoftTenantId`、Apple `kid`/`teamId`、企微 V2 `loginType`/`lang`、钉钉 V2 `dingTalkCorpId`/`dingTalkExclusiveLogin`、Okta `domainPrefix`/`authServerId`、喜马拉雅 `deviceId`/`clientOsType`/`packId` 等）。
_Avoid_: SocialAppProperties, OAuthClientConfig

**SocialAuthController**:
开箱即用受管 REST 控制器，受 `ateng.boot.social.controller.enabled=true` 守卫装配。端点严格遵循 ADR-0002 显式返回强类型 `Result<T>`，覆盖 `/render`、`/callback`（同时支持 `GET` 与 `POST` `form_post`）、`/direct-login`、`/bind`、`/bind-direct`、`/unbind`、`/refresh-token`（兼容 `/refresh`）、`/revoke-token`（兼容 `/revoke`）与 `/bindings`。
_Avoid_: SocialController, OAuthController, AuthEndpoint

**SocialAlreadyBoundException**:
跨号抢占冲突受控业务异常（错误码 `7007`），当待绑定的第三方社交账号已被其他本地用户绑定时触发刚性阻断，彻底杜绝串号越权与账号覆盖风险。
_Avoid_: AccountConflictException, SocialDuplicateException

**SocialRedirectUriInvalidException**:
开放重定向域名白名单校验受控异常（错误码 `7008`），当授权回调重定向地址域名不在系统允许的白名单内时触发拦截，防御开放重定向与网络钓鱼。
_Avoid_: RedirectException, InvalidDomainException

**SocialSnapshotUserException**:
微信快照页虚拟账号拒绝受控业务异常（错误码 `7009`），当平台返回 `snapshotUser=true`（`is_snapshotuser=1`）且开启 `rejectSnapshotUser` 防卫时刚性阻断，防止虚拟假 OpenID 污染生产用户表。
_Avoid_: FakeUserException, WechatVirtualUserException

**SocialAuthenticatedEvent / SocialBoundEvent / SocialUnboundEvent / SocialBindTicketIssuedEvent**:
Spring 标准领域事件四件套，在用户通过社交认证、绑定完成、解绑完成及绑定票据签发时通过 `ApplicationEventPublisher` 触发广播，支撑异步操作日志审计、安全风控与积分激励。
_Avoid_: SocialEvent, AuthMessage, SocialAuditNotice
