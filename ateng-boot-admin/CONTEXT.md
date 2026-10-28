# Admin Context

`ateng-boot-admin` 负责面向生产就绪的 Spring Boot Admin 企业级运维管控中心与看板基础设施，提供基于 Sa-Token 的原生控制台安全守卫、防 SSRF 实例注册防火墙、端到端双向安全凭据穿透、在线动态修改日志级别、30 秒防抖队列与 K8s 优雅下线发版静默告警状态机。

## Language

**AdminSsrfGuardFilter**:
部署于 SBA 服务端最前沿的实例注册安全防火墙过滤器，优先级 `HIGHEST_PRECEDENCE + 50`。对 `POST /instances` 注册请求强制核验 `X-Admin-Client-Secret` 集群防伪标头，并深度解析请求体中的服务地址，严密阻断公有云实例元数据网段（`169.254.169.254` 等）与非法内网渗透探测。
_Avoid_: SsrfFilter, AdminFirewall, RegistrationSecurityFilter

**ResettableContentCachingRequestWrapper**:
继承自 Spring `ContentCachingRequestWrapper` 的可复位流包装器，重写 `getInputStream()` 与 `getReader()`。支持在前置过滤器中无损读取请求体字节并进行重置，彻底杜绝下游 Controller 反序列化时产生 `EOFException` 单向流耗尽崩溃。
_Avoid_: ReusableRequestWrapper, CachedBodyRequest, RequestWrapper

**AtengActuatorHttpHeadersProvider**:
基于 SBA 官方 `HttpHeadersProvider` 扩展的服务端出站端点抓取凭据提供者。在反向代理抓取微服务度量或下发指令时，自动从目标实例元数据中提取 `actuator-token` 挂载出站标头 `X-Actuator-Token`，透明穿透客户端加固的 Actuator 端点。
_Avoid_: AdminHeaderProvider, ActuatorTokenProvider, OutboundHeaderCustomizer

**AtengAdminNotificationNotifier**:
继承自 SBA `AbstractEventNotifier` 的高可用告警状态机通知器。内置 30 秒防抖队列合并抖动虚警、监听 `InstanceDeregisteredEvent` 触发 60 秒发版静默期消除 K8s 滚动更新误报，并联动 `NotificationTemplate` 推送企业微信/钉钉/飞书结构化卡片告警。
_Avoid_: DebounceNotifier, AdminAlertNotifier, SbaAlarmManager

**AdminAuthProvider**:
SBA 控制台看板管理用户可拔插认证 SPI 接口，定义 `authenticate(username, password)` 契约。默认提供基于配置属性的比对实现，支持业务方平滑覆写接管为数据库、LDAP 或统一认证中心对接。
_Avoid_: AdminAuthenticationManager, SbaLoginService, AuthValidator

**AdminLoginController**:
纯原生内嵌的高颜值拟物化响应式登录控制器，直接映射 `/admin/login` 与 `/admin/logout` 端点。提供自洽的开箱即用玻璃拟物化前端页面，零 Spring Security、零 Thymeleaf/Freemarker 依赖。
_Avoid_: SbaLoginController, AdminAuthController, ConsolePageController

**AtengAdminServerProperties**:
SBA 服务端强类型配置属性类，配置前缀 `ateng.boot.admin.server`。纳管看板用户账密、集群防伪密钥、默认抓取凭据、`Duration` 强类型防抖时间与本地实例放行开关。
_Avoid_: AdminProperties, SbaConfig, AdminSettings

**AtengAdminServerAutoConfiguration**:
Spring Boot Admin 服务端防御性自动装配类，统一注册于 `AutoConfiguration.imports`。在保障缺省平滑覆盖的前提下装配路由守卫、登录控制器、注册防火墙、凭据提供者与告警通知器。
_Avoid_: AdminAutoConfiguration, SbaServerConfiguration, AdminConfiguration
