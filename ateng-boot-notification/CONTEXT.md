# Notification Context

`ateng-boot-notification` 负责提供企业级协同办公群机器人（企业微信、钉钉、飞书）Webhook 统一推送、统一强类型卡片自适应渲染、加签安全防刷、客户端频控、声明式防风暴异常告警切面、本地事务感知发信与四维上下文透传基础设施。

## Language

**NotificationTemplate**:
协同通知操作核心门面具体类，提供命令式纯净 API（`send`、`sendAsync`、`sendAfterCommit`、`sendTemplate`）、统一领域消息分发、四维跨线程上下文透传及本地事务感知发信。
_Avoid_: NotificationUtil, RobotHelper, DingTalkClient, WeComSender, FeishuBot, NotificationService

**NotificationMessage**:
协同通知领域消息抽象顶层契约，封装通用目标渠道、@人 目标（`MentionTarget`）及元数据。
_Avoid_: BaseMessage, MsgDTO, WebhookPayload, RobotMessage

**TextMessage**:
纯文本通知领域消息模型，承载纯文本正文与 @人 目标。
_Avoid_: TextPayload, PlainTextMessage, SimpleMessage

**MarkdownMessage**:
Markdown 格式通知领域消息模型，承载标题、Markdown 正文及 @人 目标，底层驱动自动抹平企微等受限平台的标签差异。
_Avoid_: MarkdownPayload, MdMessage, RichTextMessage

**CardMessage**:
统一跨平台交互卡片领域消息模型，包含标题、正文摘要、卡片主题色（`headerColor`）、主图 URL、按钮列表（`List<CardButton>`）与单行动跳转（`singleAction`），由平台驱动自适应转译为钉钉 ActionCard、企微 TemplateCard 与飞书 Interactive Card v2。
_Avoid_: ActionCard, TemplateCard, InteractiveCard, FeishuCard, DingCard

**ImageMessage**:
图片通知领域消息模型，结构化封装图片 Base64 编码与 MD5 校验和。
_Avoid_: ImgPayload, PicMessage, MediaMessage

**RawMessage**:
原始特化报文逃生舱领域模型，允许业务直接传入平台特定的原生 JSON 载荷以利用平台特有的极限高阶特性。
_Avoid_: JsonMessage, CustomPayload, NativeMessage

**LinkMessage**:
轻量图文链接通知领域消息模型，承载标题（`title`）、文本摘要（`text`）、封面图 URL（`picUrl`）、点击跳转直达链接（`messageUrl`）及 @人 目标，由平台驱动自适应转译为钉钉 Link、企微 News 或飞书轻量卡片。
_Avoid_: NewsMessage, UrlMessage, ArticleMessage, WebPageMessage

**CardButtonOrientation**:
交互卡片按钮排版方向枚举，定义按钮在卡片底部的排列布局（`VERTICAL` 竖排，`HORIZONTAL` 横向并排），自适应映射为钉钉 `btnOrientation` 及各端排版规范。
_Avoid_: BtnLayout, ButtonDirection, ActionOrientation

**CardButton**:
交互卡片操作按钮实体模型，封装按钮显示文本、跳转链接 URL 与样式类型（`PRIMARY`、`DEFAULT`、`DANGER`）。
_Avoid_: ActionBtn, CardAction, LinkButton

**MentionTarget**:
@人 目标领域对象，统一封装 `@all` 标识、手机号集合（`atMobiles`）与工号/OpenID 集合（`atUserIds`），底层驱动自动转译为平台专属语法或请求体属性。
_Avoid_: AtUsers, MentionDTO, AtTarget, RobotAt

**NotificationChannel**:
通知渠道运行时配置模型，封装渠道名称、平台类型（`Platform`）、Webhook URL、加签 Secret 与频控配额。
_Avoid_: RobotConfig, ChannelProfile, WebhookSetting

**NotificationChannelRegistry**:
通知渠道静态注册中心与物理客户端缓存容器，管理预热的渠道配置并提供并发安全存取。
_Avoid_: ChannelManager, RobotRegistry, WebhookPool

**NotificationChannelResolver**:
通知渠道动态路由解析器 SPI，支持依据多租户上下文（`TenantContextHolder`）或动态业务标签推导渠道名称。
_Avoid_: ChannelRouter, RobotSelector, TenantChannelResolver

**NotificationChannelProvider**:
通知渠道动态加载供给器 SPI，提供依据渠道名称从外部介质（数据库、配置中心等）按需懒加载 `NotificationChannel` 的能力。
_Avoid_: ChannelRepository, RobotLoader, ChannelService

**NotificationProvider**:
平台驱动通信引擎 SPI，抽象 `supports(Platform)` 协议协商与 `send(NotificationChannel, NotificationMessage, RestClient)` 发信契约。
_Avoid_: RobotSender, NotificationDriver, WebhookClient

**WeComNotificationProvider**:
企业微信群机器人驱动实现，内置特化报文构建、Markdown 标签防崩清洗与图片 MD5 组装。
_Avoid_: QywxDriver, WechatRobot, WeComClient

**DingTalkNotificationProvider**:
钉钉群机器人驱动实现，内置 HMAC-SHA256 URL 签名计算、ActionCard 单双按钮自适应与频控防卫。
_Avoid_: DingDriver, DingTalkRobot, DingClient

**FeishuNotificationProvider**:
飞书群机器人驱动实现，内置请求体签名计算、Interactive v2 卡片构建与正文 `<at>` 标签自适应注入。
_Avoid_: LarkDriver, FeishuRobot, LarkClient

**GenericWebhookNotificationProvider**:
通用自定义 JSON Webhook 驱动实现，面向开发者自建告警网关或第三方标准化端点。
_Avoid_: CustomDriver, HttpWebhookProvider

**NotificationRateLimiter**:
客户端通道频控器接口，负责单通道发信速率防卫并提供微退避排队能力（`tryAcquire`）。
_Avoid_: Limiter, RateLimitingService, RobotThrottler

**InMemoryNotificationRateLimiter**:
基于内存滑动窗口/令牌桶算法的默认本地频控器实现。
_Avoid_: LocalLimiter, SimpleRateLimiter

**NotificationContextHolder**:
线程级动态切源上下文持有者，提供线程级隐式切换当前生效渠道名称的机制（`runWithChannel`）。
_Avoid_: ChannelContext, NotificationHolder, RobotContext

**NotifyAlert**:
声明式异常告警切面注解，标注于 Controller、Service 或 `@Scheduled` 方法，在抛出未受控异常时自动采集堆栈与链路上下文并推送卡片告警。
_Avoid_: AlertOnException, ErrorNotify, ExceptionAlarm

**NotifyAlertAspect**:
声明式异常告警切面，负责异常捕获、方法入参安全脱敏、TraceId 提取与告警卡片装配。
_Avoid_: AlertInterceptor, ErrorNotifyAspect

**AlertStormSuppressor**:
防告警风暴抑制器，基于 MD5 异常指纹实施“首发即时通报 + 窗口期原子计数拦截 + 周期末聚合通报”抑制算法。
_Avoid_: AlertAggregator, StormFilter, Deduplicator

**NotificationSendResult**:
通知发信执行结果领域载荷，结构化记录消息 ID、渠道名称、平台类型、发信状态、耗时及平台原始响应。
_Avoid_: SendResult, RobotResponse, NotifyResult

**NotificationSendFailureHandler**:
事务感知发信（`sendAfterCommit`）与受管异步发信失败补偿处理器 SPI，提供发送失败时的降级落库或告警处理。
_Avoid_: FailureCallback, NotifyCompensator, ErrorHandler

**NotificationTemplateRenderer**:
通知模板动态渲染引擎 SPI，解耦具体模板引擎实现，内置极简正则占位符替换保底，条件支持 Thymeleaf 与 Freemarker。
_Avoid_: TemplateEngine, NotifyRenderer, CardTemplateEngine

**SimpleNotificationTemplateRenderer**:
纯原生轻量占位符插值渲染器，支持 `${variable}` 格式的极简文本安全替换。
_Avoid_: RegexRenderer, StringReplacer

**NotificationSendEvent**:
Spring 原生通知生命周期事件基类（含 `NotificationSendingEvent`、`NotificationSentEvent`、`NotificationSendFailedEvent` 与 `NotificationChannelEvictEvent`）。
_Avoid_: NotifyEvent, RobotAuditRecord
