# Mail Context

`ateng-boot-mail` 负责提供企业级纯原生、工业级异步邮件与模板通知基础设施，涵盖多账号动态路由、受管异步四维上下文透传、模板引擎解耦渲染、RFC 6266 大附件流式安全装配、本地事务感知发信与全生命周期审计事件广播。

## Language

**MailTemplate**:
邮件通信操作核心门面具体类，提供命令式纯净 API、HTML 模板动态渲染、流式大附件装配、受管异步四维上下文透传及本地事务感知发信（`sendAfterCommit`）。
_Avoid_: JavaMailSender, MailUtil, EmailHelper, MailClient

**MailAccount**:
发件源账号领域实体与运行时配置模型，封装 SMTP 主机、端口、认证凭据、SSL/TLS 协议安全参数、默认发件人以及底层池化物理 `JavaMailSender` 实例映射。
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
发件源账号静态注册中心与底层物理 Sender 缓存容器，管理预热的 `JavaMailSenderImpl` 实例池并提供并发安全存取。
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

**MailPriority**:
强类型邮件重要性与优先级枚举，统一抽象标准 RFC 3798 / RFC 2156 邮件标头规范（映射 `X-Priority` 与 `Importance`）。
_Avoid_: EmailPriority, PriorityLevel, MessagePriority

**MailSandboxInterceptor**:
邮件测试沙箱安全拦截器，专职在非生产环境中通过白名单通配放行、安全测试邮箱强制重定向与纯日志模拟杜绝向真实用户误发邮件。
_Avoid_: MailDevFilter, SandboxFilter, TestMailInterceptor

**MailSendException**:
邮件发送统一语义化业务异常（继承 Core 模块 `BusinessException`），拦截底层 JavaMail `MessagingException` / `MailAuthenticationException` 并完整保留原始因果链。
_Avoid_: MessagingException, MailException, EmailException
