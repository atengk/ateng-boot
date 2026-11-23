# Admin Context

`ateng-boot-admin` 是面向生产就绪的 Spring Boot Admin 企业级运维管控中心与看板基础设施，独立于 Spring Security 运行，提供多角色控制台鉴权与防爆破、入站与出站双向 SSRF 防线、细粒度端点访问守卫、操作审计、多模态出站抓取穿透、生命周期清理、多维告警防风暴以及可选集群协调能力。

## Language

### 入站准入与 SSRF 防线

**Cluster Admission Secret (集群准入防伪密钥)**:
客户端微服务在注册与注销生命周期通信中携带的共享握手凭据（`X-Admin-Client-Secret`），用于在准入评估与注销鉴权阶段核验节点合法性。
_Avoid_: Actuator Token, Console Password, Auth Token

**Instance Registration Snapshot (实例注册快照)**:
在 HTTP 注册边界预先解析生成的不可变领域注册描述模型（`InstanceRegistration`），供准入策略评估后再交由 SBA 注册表持久化。
_Avoid_: SBA Registration, RegistrationDTO, InstanceEntity

**Registration Admission Policy (实例注册准入策略)**:
面向实例注册请求与服务发现实例的可拔插评估契约（`AdminInstanceAdmissionPolicy`），依据注册快照、地址安全规则与入站请求返回放行或携带状态码与拒绝原因的准入决策结果（`AdminAdmissionResult`）。
_Avoid_: RegistrationPolicy, InstanceValidator, AdmissionGuard

**SSRF Registration Firewall (防 SSRF 注册防火墙)**:
部署于 SBA 上下文注册与注销入口前哨的安全过滤防线（`AdminSsrfGuardFilter`），负责执行有界载荷检查、空载荷拒止、协议与云厂商元数据网段硬阻断、可控 DNS 解析校验及未授权注销拦截。
_Avoid_: SsrfFilter, AdminFirewall, RegistrationSecurityFilter

**Resettable Request Stream (可复位请求流)**:
支持前置安全过滤防线在有界容量保护下无损读取注册请求载荷、同时保持下游控制器可重复消费流数据的请求包装契约（`ResettableContentCachingRequestWrapper`）。
_Avoid_: ReusableRequestWrapper, CachedBodyRequest, ContentCachingRequestWrapper

**Discovery Admission Guard (服务发现准入守卫)**:
面向通过 `DiscoveryClient` 等服务发现机制接入的实例所施加的统一地址安全与命名准入防线，豁免 HTTP 握手密钥头但强制执行同等 SSRF 与命名策略审查。
_Avoid_: DiscoveryFilter, CloudInstanceValidator

**Outbound SSRF Guard (出站代理 SSRF 复检防线)**:
在 SBA 服务端通过反向代理或后台探活向受管实例发起真实出站 HTTP 请求前执行的二次目标地址校验防线，用于阻断异步 DNS 重绑定及已持久化异常地址探测。
_Avoid_: ProxySsrfInterceptor, WebClientFirewall, OutboundFilter

### 控制台鉴权、RBAC 与端点治理

**Console Authentication (控制台登录认证)**:
面向运维监控看板操作人员的身份校验扩展契约（`AdminAuthProvider`）与纯原生表单登录、多账号口令校验及 Sa-Token 会话治理机制，与集群准入密钥及出站抓取凭据相互独立。
_Avoid_: AdminAuthenticationManager, SbaLoginService, AuthValidator, Cluster Admission Secret

**Console Principal (控制台操作主体)**:
通过控制台登录认证后签发的不可变身份承载模型，封装操作人唯一标识、角色集合及展示摘要，并同步桥接至 Sa-Token 会话与 SBA 原生前端请求主体。
_Avoid_: AdminUser, AuthUser, SessionUser

**Console Role (控制台权限角色)**:
控制台人员的三级互斥/包含权限分级模型，包含只读观察员（`VIEWER`）、运维操作员（`OPERATOR`）与系统管理员（`ADMIN`），决定可调用的控制台管理接口与 Actuator 代理写权限。
_Avoid_: SecurityRole, PermissionLevel, AdminGroup

**Login Brute-Force Guard (登录防爆破守卫)**:
针对控制台表单登录端点实施的账号与客户端 IP 双轨连续失败计数及临时锁定熔断机制，支持基于可信代理网段提取真实源 IP 并回显锁定剩余提示。
_Avoid_: LoginLimiter, CaptchaGuard, PasswordRetryCache

**Console Endpoint Guard (控制台端点访问守卫)**:
在控制台反代边界综合全局写开关、当前主体角色与高危端点黑名单（默认含 `heapdump`/`shutdown`/`restart`）对 Actuator 代理请求及敏感管理操作实施前置拦截的安全防线，同时严格放行合法实例生命周期通信。
_Avoid_: WriteGuardFilter, ReadOnlyFilter, OperationGuardFilter, ConsoleWriteFilter

**Console Operation Audit (控制台操作审计)**:
对控制台登录、准入拒绝、高危端点拦截、Actuator 代理变更、实例手动注销与静默管理等关键安全与运维动作生成的结构化审计轨迹，支持领域事件广播与有界内存追溯。
_Avoid_: AdminLog, OperationHistory, ActionRecorder

### 出站探测与实例生命周期

**Outbound Actuator Credential Cascade (出站 Actuator 凭据级联推导)**:
监控中心向受管微服务抓取 Actuator 端点时，作为唯一权威标头解析器按 `Basic -> Bearer/JWT -> 自定义 Token 请求头 -> 服务端按服务映射或全局兜底` 优先级自适应推导并注入认证请求头的机制（`AtengActuatorHttpHeadersProvider`）。
_Avoid_: AdminHeaderProvider, ActuatorTokenProvider, Cluster Admission Secret

**Zombie Instance Eviction (僵尸实例驱逐清理)**:
独立受管调度器周期扫描并确定性注销持续处于 `OFFLINE` 或 `UNKNOWN` 状态且超出滞留阈值的失联实例，并联动清理事件存储与通知器残留状态的生命周期治理机制（`ZombieInstanceCleanupScheduler`）。
_Avoid_: ZombieCleaner, InstanceGarbageCollector, Graceful Deregistration Silence

### 防抖告警、防风暴与集群协调

**Fault Debounce Window (故障防抖窗口)**:
实例跃迁至目标故障状态后暂缓发信的可配置毫秒级延时窗口（`debounce-duration`），若实例在窗口期内自愈恢复则撤销挂起告警且不产生恢复噪音（`AtengAdminNotificationNotifier`）。
_Avoid_: 发版静默期, 维护静默窗口, 名单过滤静默, DebounceNotifier

**Graceful Deregistration Silence (优雅下线发版静默期)**:
由受控注销事件（`InstanceDeregisteredEvent`）触发的可配置静默窗口，用于在滚动发布优雅停机期间屏蔽后续下线震荡误报。
_Avoid_: 防抖窗口, 维护静默窗口, 僵尸清理, Alert Rule Filtering

**Alert Rule Filtering (告警规则名单过滤)**:
在告警防抖入队前与超时发送前依据服务名通配符黑白名单及目标状态白名单对状态跃迁事件执行的静态静默过滤机制。
_Avoid_: 防抖静默, 发版静默, 维护静默窗口, 准入白名单

**Maintenance Silence Window (维护静默窗口)**:
面向计划内变更或临时故障处置场景提供的运行时可增删查的按服务模式与有效期临时屏蔽告警通道的动态静默机制，与静态黑白名单规则正交分离。
_Avoid_: MuteRule, AlertFilter, 防抖窗口, 发版静默期

**Alert Storm Aggregation (告警雪崩折叠聚合)**:
当同一时间窗内发生故障的实例数量达到预设雪崩阈值时，将同源散落单实例告警自动折叠汇总为单张群组摘要卡片以防止通知渠道限流风暴的降噪机制。
_Avoid_: BatchAlert, StormSuppressor, DebounceMerge

**Fault Escalation Reminder (持续故障周期重提醒)**:
对已确认告警且长期持续未恢复 `UP` 的故障实例，在满足间隔与最大重提醒次数上限约束下周期性推送橙色催办提示的状态跟踪机制。
_Avoid_: RepeatAlert, RetryNotifier, DebounceWindow

**Cluster Coordination Lock (集群防脑裂协调锁)**:
在多副本服务端部署下，以“无 Redis 单机内存自洽、有 Redis 异常 Fail-Open 放行”为原则协调告警去重发信、僵尸实例清理互斥与维护静默规则同步的分布式协调契约。
_Avoid_: DistributedLock, RedisAdminLock, ClusterMutex

**Low-Cardinality Admin Observability (低基数 Admin 自观测探针)**:
通过禁止注入 `instanceId`、URL、IP 或用户名等动态无界标签的 Micrometer 指标绑定器（`AdminMetricsBinder`）与聚合仓库/通知器/清理器/静默状态摘要的健康指示器（`AdminServerHealthIndicator`），向外暴露监控中心自身运行健康度与关键治理计数。
_Avoid_: PerInstanceMetrics, UnboundedAdminTags, AdminActuatorScrapedMetrics
