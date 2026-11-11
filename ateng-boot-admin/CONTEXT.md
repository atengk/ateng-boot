# Admin Context

`ateng-boot-admin` 是面向生产就绪的 Spring Boot Admin 企业级运维管控中心与看板基础设施，独立于 Spring Security 运行，提供控制台安全鉴权、防 SSRF 注册防线、只读操作管控、出站多模态抓取穿透、僵尸实例清理与防抖静默告警能力。

## Language

### 入站安全与准入

**Cluster Admission Secret (集群准入防伪密钥)**:
客户端微服务在实例注册请求中携带的共享握手凭据（`X-Admin-Client-Secret`），用于在准入评估阶段核验集群接入合法性。
_Avoid_: Actuator Token, Console Password, Auth Token

**Instance Registration Snapshot (实例注册快照)**:
在 HTTP 注册边界预先解析生成的不可变领域注册描述模型（`InstanceRegistration`），供准入策略评估后再交由 SBA 注册表持久化。
_Avoid_: SBA Registration, RegistrationDTO, InstanceEntity

**Registration Admission Policy (实例注册准入策略)**:
面向实例注册请求的可拔插评估契约（`AdminInstanceAdmissionPolicy`），依据注册快照与入站请求返回放行或携带状态码与拒绝原因的准入决策结果（`AdminAdmissionResult`）。
_Avoid_: RegistrationPolicy, InstanceValidator, AdmissionGuard

**SSRF Registration Firewall (防 SSRF 注册防火墙)**:
部署于实例注册入口前哨的安全过滤防线（`AdminSsrfGuardFilter`），负责阻断云厂商元数据网段、非法协议及未授权环回地址渗透，并防御超大或畸形注册载荷。
_Avoid_: SsrfFilter, AdminFirewall, RegistrationSecurityFilter

**Resettable Request Stream (可复位请求流)**:
支持前置安全过滤防线在有界容量保护下无损读取注册请求载荷、同时保持下游控制器可重复消费流数据的请求包装契约（`ResettableContentCachingRequestWrapper`）。
_Avoid_: ReusableRequestWrapper, CachedBodyRequest, ContentCachingRequestWrapper

### 控制台鉴权与只读管控

**Console Authentication (控制台登录认证)**:
面向运维监控看板操作人员的身份校验扩展契约（`AdminAuthProvider`）与纯原生表单登录及 Sa-Token 会话治理机制（`AdminLoginController`），与集群准入密钥及出站抓取凭据相互独立。
_Avoid_: AdminAuthenticationManager, SbaLoginService, AuthValidator, Cluster Admission Secret

**Console Write Guard (控制台只读写守卫)**:
在只读策略激活时前置拦截针对微服务 Actuator 反向代理端点的非幂等写请求、同时严格豁免实例注册与注销生命周期通信的安全防线（`AdminWriteGuardFilter`）。
_Avoid_: ReadOnlyFilter, OperationGuardFilter, ConsoleWriteFilter

### 出站探测与实例生命周期

**Outbound Actuator Credential Cascade (出站 Actuator 凭据级联推导)**:
监控中心向受管微服务抓取 Actuator 端点时，按 `Basic -> Bearer/JWT -> X-Actuator-Token -> 全局兜底` 优先级自适应推导并注入认证请求头的机制（`AtengActuatorHttpHeadersProvider`）。
_Avoid_: AdminHeaderProvider, ActuatorTokenProvider, Cluster Admission Secret

**Zombie Instance Eviction (僵尸实例驱逐清理)**:
受管调度器周期扫描并确定性注销持续处于 `OFFLINE` 或 `UNKNOWN` 状态且超出滞留阈值的失联实例的生命周期治理机制（`ZombieInstanceCleanupScheduler`）。
_Avoid_: ZombieCleaner, InstanceGarbageCollector, Graceful Deregistration Silence

### 防抖告警与降噪状态机

**Fault Debounce Window (故障防抖窗口)**:
实例跃迁至目标故障状态后暂缓发信的可配置延时窗口（`debounce-duration`），若实例在窗口期内自愈恢复则撤销挂起告警且不产生恢复噪音（`AtengAdminNotificationNotifier`）。
_Avoid_: 发版静默期, 名单过滤静默, DebounceNotifier

**Graceful Deregistration Silence (优雅下线发版静默期)**:
由受控注销事件（`InstanceDeregisteredEvent`）触发的固定静默窗口，用于在滚动发布优雅停机期间屏蔽后续下线震荡误报。
_Avoid_: 防抖窗口, 僵尸清理, Alert Rule Filtering

**Alert Rule Filtering (告警规则名单过滤)**:
在告警防抖入队前与超时发送前依据服务名通配符黑白名单及目标状态白名单对状态跃迁事件执行的静默过滤机制。
_Avoid_: 防抖静默, 发版静默, 准入白名单
