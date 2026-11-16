# Mail Context

`ateng-boot-mail` 负责提供企业级纯原生、工业级异步邮件与模板通知基础设施，涵盖多账号动态路由、受管异步四维上下文透传、模板引擎解耦渲染、RFC 6266 大附件流式安全装配、本地事务感知发信与全生命周期审计事件广播。

## Language

**MailTemplate**:
邮件通信操作核心门面具体类，提供命令式纯净 API、HTML 模板动态渲染、流式大附件装配、受管异步四维上下文透传及本地事务感知发信（`sendAfterCommit`）。
_Avoid_: JavaMailSender, MailUtil, EmailHelper, MailClient

**MailAccount**:
发件源账号领域实体与运行时配置模型，封装 SMTP 主机、端口、认证凭据、SSL/TLS 协议安全参数、默认发件人、有序主备容灾列表（`backupAccounts`）以及底层池化物理 `JavaMailSender` 实例映射。
_Avoid_: EmailConfig, SmtpProperties, MailProfile, MailSender

**MailMessage**:
强类型邮件领域模型，结构化封装发件账号路由标识、发件人、收件人列表（To/Cc/Bcc）、主题、文本/HTML 内容、流式附件集合（`MailAttachment`）、内联资源（Inline CID）及自定义邮件标头。
_Avoid_: MimeMessage, Email, SimpleMailMessage, MailDTO

**MailAttachment**:
邮件流式附件领域抽象，支持基于 `File`、`InputStreamSource` 及 Spring 原生 `Resource`（如 `StorageResource`）的流式挂载、RFC 6266 文件名清洗、MIME 推导与确定性生命周期清理。
_Avoid_: Attachment, EmailFile, FileAttachment

**MailInlineResource**:
正文内嵌资源领域模型，专职用于富文本 HTML 中通过 `cid:` 引用的图片与静态资源，强制绑定唯一的 Content-ID，与普通下载附件职责正交。
_Avoid_: InlineAttachment, EmbeddedImage, CidResource

**MailAccountRegistry**:
发信账号静态注册中心与底层 `JavaMailSenderImpl` 配置及物理发信器 LRU 缓存容器（基于 `ReentrantLock` 并发安全纳管静态与动态账号，并提供 `testConnection` 探活与 `XOAUTH2` 令牌刷新重载辅助；不同于批量发信期间按分片临时借还的 `Transport` 长连接复用）。
_Avoid_: AccountManager, MailSenderPool, EmailRegistry

**MailAccountResolver**:
发件账号动态路由解析器 SPI，支持依据多租户上下文（`TenantContextHolder`）或自定义业务标签动态解析发信账号。
_Avoid_: AccountRouter, MailSelector, TenantAccountProvider

**MailAccountProvider**:
发件账号动态加载供给器 SPI，提供依据逻辑账号标识从外部数据源（数据库、配置中心等）按需懒加载 `MailAccount` 领域模型的能力。
_Avoid_: AccountRepository, MailConfigLoader, AccountFetcher, MailAccountService

**MailSendFailureHandler**:
事务感知发信（`sendAfterCommit`）与受管异步发信失败补偿处理器 SPI，提供最终失败时的可靠持久化或告警降级入口。
_Avoid_: FailureCallback, MailCompensator, ErrorHandler

**MailTemplateRenderer**:
邮件富文本模板动态渲染引擎 SPI，解耦具体模板实现，支持 Thymeleaf 与 Freemarker 外部引擎条件装配，并提供内置轻量正则替换引擎保底。
_Avoid_: TemplateEngine, MailEngine, FreeMarkerRenderer, ThymeleafView

**SimpleMailTemplateRenderer**:
纯原生轻量占位符插值渲染器，作为零外部模板引擎运行环境下的保底实现，支持 `${variable}` 格式的极简文本安全替换。
_Avoid_: RegexTemplateEngine, DefaultRenderer, StringReplacer

**MailSendResult**:
邮件发信执行结果领域载荷，结构化记录全局 Message-ID、投递状态、目标收件人、执行耗时及时间戳。
_Avoid_: SendResult, EmailResponse, MailStatus

**MailSendEvent**:
Spring 原生邮件发信生命周期事件基类（含 `MailSendingEvent`、`MailSentEvent` 与 `MailSendFailedEvent`），提供纯净的业务持久化审计台账与告警广播解耦。
_Avoid_: EmailEvent, MailAuditRecord, SendCallback

**MailAccountEvictEvent**:
Spring 原生发信账号缓存驱逐领域事件，支持单机或分布式集群（通过消息总线广播）联动清理动态加载的 `MailAccount` 及其物理 Sender 缓存。
_Avoid_: AccountCacheClearEvent, MailAccountInvalidateEvent, AccountEvictEvent

**MailSendInterceptor**:
物理发信拦截器 SPI，支持发信前置黑名单过滤、标头动态改写与频控配额拦截。
_Avoid_: MailFilter, SendInterceptor, EmailInterceptor

**BatchMailSendResult**:
批量个性化发信执行聚合回执领域模型，结构化记录批次总数、成功数、失败数、总执行耗时及单封邮件投递明细（`MailSendResultItem`）。
_Avoid_: BatchSendResult, MultiMailResponse, SendBatchResult

**MailSendResultItem**:
批量邮件合并投递单项回执模型，封装单个收件人投递的 Message-ID、收件地址、发送耗时、成功状态与失败因果异常，支撑 Fail-Safe 容错聚合。
_Avoid_: BatchItem, MailItemResult, SendResultItem

**DynamicMailSession**:
动态临时账号发信会话门面，由 `MailTemplate.forDynamicAccount` 派生，为指定运行时发件账号提供对称完整的单封/异步/事务感知（`sendAfterCommit`/`sendAsyncAfterCommit`）、物理连通性探针（`testConnection`/`testConnectionOrThrow`）及绑定账号的批量合并（`sendBatch`/`sendBatchAsync`/`sendBatchAfterCommit`/`sendBatchAsyncAfterCommit`）能力。
_Avoid_: MailSession, AccountSession, MailClientSession

**MailPriority**:
强类型邮件重要性与优先级枚举，统一抽象主流邮件客户端识别的 `X-Priority` / `Importance` 标头规范（与 `RFC 3798` 阅读回执正交配合）。
_Avoid_: EmailPriority, PriorityLevel, MessagePriority

**MailSandboxInterceptor**:
邮件测试沙箱安全拦截器，专职在非生产环境中通过白名单通配放行、安全测试邮箱强制重定向与纯日志模拟杜绝向真实用户误发邮件。
_Avoid_: MailDevFilter, SandboxFilter, TestMailInterceptor

**MailSendException**:
邮件发送统一语义化业务异常（继承 Core 模块 `BusinessException`），拦截底层 JavaMail `MessagingException` / `MailAuthenticationException` 并完整保留原始因果链。
_Avoid_: MessagingException, MailException, EmailException

**MailRateLimiter**:
邮件出站频率限制 SPI，融合发信账号维度令牌桶平滑限流与单收件人维度滑动窗口防轰炸，按去重后的实际物理收件人次计费拦截（触发 `MAIL_RATE_LIMITED`），内置单机内存与 Redis 分布式双模实现。
_Avoid_: RateLimiter, MailThrottler, EmailRateLimit

**MailDailyQuotaBreaker**:
邮件自然日总配额硬熔断器 SPI，按自然日累计去重物理收件人次，触顶拦截（触发 `MAIL_QUOTA_EXCEEDED`）并驱动同步发布 `MailQuotaExceededEvent` 告警事件，支持跨日自动重置。
_Avoid_: QuotaManager, DailyLimitBreaker, MailQuotaService

**MailQuotaExceededEvent**:
邮件自然日总配额耗尽熔断解耦领域事件，在总配额达到上限拦截时由 `MailTemplate` 同步发布，供业务按需监听告警而不强耦合通知子系统。
_Avoid_: QuotaAlertEvent, MailLimitWarningEvent

**MailIdempotentGuard**:
邮件出站幂等防重守卫 SPI，默认在 `require-biz-id=true` 时依据业务流水号 `bizId` 抢占独占锁（重复拦截抛出 `MAIL_IDEMPOTENT_REJECTED`），物理发信失败时主动执行 `releaseLock` 以支持补偿重试。
_Avoid_: IdempotentLock, MailDeduplicator, DuplicateMailFilter

**MailResultCode**:
邮件模块统一语义化业务错误码枚举（实现 `IResultCode`），涵盖 `40001~40009`（收件人为空、账号未找到、附件超限、模板渲染失败、总容量超限、地址格式非法、频控拦截、日配额耗尽、幂等拒绝）与 `50001~50003`（发送失败、连接超时、认证失败）。
_Avoid_: MailErrorCode, EmailStatusCode, MailFailureEnum

**AuthMode**:
SMTP 传输认证模式强类型枚举（实现 `IBaseEnum<String>`），定义 `AUTO`（存在用户名/密码时启用密码认证，否则免认证中继）、`PASSWORD`、`NONE`（免认证内网中继）与 `XOAUTH2`（OAuth2 访问令牌认证）。
_Avoid_: SmtpAuthType, AuthStrategy, LoginMode

**MailAccessTokenProvider**:
SMTP `XOAUTH2` 动态访问令牌供给与刷新 SPI（`@FunctionalInterface`），提供 `getAccessToken` 与首次认证失败后自动触发的 `refreshAccessToken` 回调契约。
_Avoid_: OAuthTokenService, TokenSupplier, MailTokenManager

**ThymeleafMailTemplateRenderer**:
基于 Spring 6 `SpringTemplateEngine` 的 Thymeleaf 邮件 HTML 模板渲染实现（实现 `MailTemplateRenderer`），配合 `MailTemplateResourceUtil` 执行 `Locale` 级联变体解析与路径安全校验。
_Avoid_: ThymeleafViewRenderer, MailThymeleafEngine

**FreemarkerMailTemplateRenderer**:
基于 Freemarker `Configuration` 的邮件模板渲染实现（实现 `MailTemplateRenderer`），自动按扩展名（`.ftl` / `.ftlh`）与 `Locale` 级联渲染 HTML 内容。
_Avoid_: FtlRenderer, FreemarkerView

**MailTemplateResourceUtil**:
邮件模板类路径探测与国际化 `Locale` 级联候选名称解析静态工具，内置防目录穿越（`..`）与空字符（`\0`）攻击拦截校验。
_Avoid_: TemplatePathHelper, FtlResourceUtil, MailTemplateScanner

**MailMetricsRecorder**:
邮件发送、物理重试与主备容灾切换度量记录 SPI，解耦 `MailTemplate` 核心引擎与具体 Micrometer 实现，未引入观测组件时默认采用空实现降级。
_Avoid_: MetricsCollector, MailStatService

**MailMetrics**:
基于 Micrometer 的邮件基础设施低基数度量采集团（实现 `MailMetricsRecorder`），导出 `ateng.mail.send.count`、`ateng.mail.send.duration`、`ateng.mail.retry.count`、`ateng.mail.failover.count` 及静态/动态账号 `Gauge`，强制收敛动态租户账号标签防 Prometheus 基数爆炸。
_Avoid_: PrometheusMailExporter, MailStatCounter

**MailSendOutcome**:
邮件发信操作终态结果枚举，严格收敛低基数 Micrometer `outcome` 标签取值（`success`、`sandbox`、`intercepted`、`rate_limited`、`quota_exceeded`、`idempotent_rejected`、`failure`）。
_Avoid_: DeliveryOutcome, SendStateTag

**TrackableMimeMessage**:
具备稳定唯一标头与追踪标识追踪能力的内部 `MimeMessage` 包装模型，确保 RFC 5322 `Message-ID` 与追踪上下文在底层的无损传递与防篡改。
_Avoid_: CustomMimeMessage, RawEmailMessage
