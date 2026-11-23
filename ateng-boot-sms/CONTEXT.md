# SMS Context

`ateng-boot-sms` 负责提供企业级专有多渠道短信发送、验证码全生命周期治理、多命名渠道拓扑、滑动窗口防刷、多维成本熔断、运营商异步回执与四维上下文透传基础设施。

## Language

**SmsTemplate**:
短信操作核心门面具体类，提供命令式纯净 API（`send`、`sendBatch`、`sendAsync`、`sendAfterCommit`、`sendDelayed`/`sendAt`/`cancelScheduled`、`querySendStatus`、`queryBalance`、`evictChannelCache`）、动态渠道路由、四维与渠道上下文透传及本地事务感知发信。
_Avoid_: SmsUtil, SmsHelper, SmsClient, SmsService

**SmsVerifyCodeTemplate**:
专职验证码全生命周期门面具体类，提供验证码生成、滑动窗口频控、发送、强类型校验核销、防暴力破解与错误冻结机制。
_Avoid_: CodeUtil, VerifyCodeHelper, CaptchaService, SmsCodeManager

**SmsProvider**:
异构云厂商短信驱动顶层 SPI 接口，封装底层 HTTP 网络通信、协议鉴权签名与厂商专有请求报文转译。
_Avoid_: SmsSupplier, SmsClient, SmsDriver, SmsSender

**AliyunSmsProvider**:
阿里云短信服务（Dysmsapi）纯原生驱动实现，基于 Spring 6 RestClient 与 POP 协议 HMAC-SHA1 签名直连官方 API。
_Avoid_: AliyunSmsClient, AliSmsSender, DysmsapiClient

**TencentSmsProvider**:
腾讯云短信（SMS 3.0）纯原生驱动实现，基于 Spring 6 RestClient 与 TC3-HMAC-SHA256 签名直连官方 API。
_Avoid_: TencentSmsClient, QcloudSmsSender, TencentCloudSms

**HuaweiSmsProvider**:
华为云消息&短信（MSGSMS）纯原生驱动实现，基于 Spring 6 RestClient 与 WSSE 规范 UsernameToken 鉴权直连官方 API。
_Avoid_: HuaweiSmsClient, HwSmsSender, MsgsmsClient

**MockSmsProvider**:
本地开发与离线测试沙箱驱动实现，纯内存状态记录与结构化日志打印，保障白板环境 100% 离线自洽。
_Avoid_: DummySmsProvider, FakeSmsClient, TestSmsSender

**SmsRequest**:
单条短信发送领域请求值对象，封装目标手机号、短信签名、模板编码/ID、模板参数与外部业务流水号。
_Avoid_: SendSmsDTO, SmsSendParam, SmsMessage

**BatchSmsRequest**:
批量/营销短信发送领域请求值对象，封装手机号集合（支持底座透明分批切片）、短信签名、模板编码/ID 与模板参数。
_Avoid_: BatchSendDTO, BulkSmsRequest, MassSmsDTO

**SmsParam**:
统一短信模板参数流式领域值对象，支持键值对 Map 与有序数组的双向构建，由底座依据厂商特性执行自适应智能推导。
_Avoid_: TemplateParam, ParamMap, SmsParamsDTO

**SmsResult**:
统一短信发送结果领域值对象，结构化封装受理状态、实际投递渠道、云厂商标识、业务流水号 BizId、厂商 RequestId、原生错误码与预估计费条数。
_Avoid_: SendResult, SmsResponse, SmsSendVO

**BatchSmsResult**:
批量短信发送聚合结果领域值对象，结构化封装总请求数、成功总数、失败手机号列表及各切片批次元数据。
_Avoid_: BulkSendResult, BatchSmsResponse, MassSmsResult

**SmsChannel**:
短信渠道定义模型，封装渠道名、绑定的云厂商类型、认证密钥凭证、默认签名、模板映射及容灾备用渠道。
_Avoid_: SmsAccount, SmsConfig, ChannelDTO

**SmsChannelRegistry**:
多命名短信渠道注册表，负责多渠道实例的生命周期纳管、缓存寻址与动态热加载。
_Avoid_: ChannelManager, SmsConfigRegistry, ChannelPool

**SmsChannelResolver**:
多租户与业务上下文短信渠道动态解析器 SPI，按“请求显式渠道 -> `SmsContextHolder` -> `TenantContextHolder` 绑定渠道 -> 默认渠道”优先级决策目标渠道（默认实现为 `TenantSmsChannelResolver`）。
_Avoid_: ChannelRouter, SmsDispatcher, SmsRouteResolver

**SmsContextHolder**:
短信渠道线程级动态切源上下文持有者，依托 ThreadLocal 显式指定当前线程使用的短信渠道。
_Avoid_: SmsThreadLocal, SmsSessionContext, ChannelHolder

**SmsRateLimiter**:
短信防刷与频控执行器，基于滑动窗口算法实施单手机号与 IP 维度的多阶频控防刷。
_Avoid_: RateLimitUtil, SmsThrottle, SmsFlowController

**SmsBlacklistProvider**:
手机号出站黑名单过滤器 SPI，在短信发送前对已退订或风控拦截手机号实施前置静默防卫。
_Avoid_: BlacklistFilter, BlockedNumberChecker, SmsShield

**DailyBudgetBreaker**:
短信单日成本预算熔断器，监控全天累计发信总量并在达到配额上限时触发硬熔断。
_Avoid_: CostGuard, QuotaBreaker, BudgetLimiter

**SmsDeliveryReportEvent**:
运营商短信送达回执领域事件，封装手机号、送达状态、回执时间与错误码。
_Avoid_: ReceiptEvent, StatusReportEvent, SmsReportNotice

**SmsMoMessageEvent**:
用户上行回复短信领域事件，封装手机号、回复文本内容与接收时间。
_Avoid_: MoEvent, UpstreamSmsEvent, ReplyMessageEvent

**SmsSendFailureHandler**:
短信最终发送失败补偿扩展点 SPI，在主通道与 Failover 容灾通道均投递失败时触发后置补偿。
_Avoid_: SendFailCallback, SmsErrorHandler, FallbackHandler

**YunpianSmsProvider**:
云片网（Yunpian）纯原生 HTTP REST 驱动实现，基于 Spring 6 RestClient 与 APIKEY 标头直连官方网关，支持单条与批量个性化群发。
_Avoid_: YunpianClient, YunpianSender

**CtyunSmsProvider**:
中国电信天翼云纯原生驱动实现，基于 Spring 6 RestClient 与天翼云 OpenAPI HmacSHA256 签名体系直连官方专线。
_Avoid_: CtyunClient, TianyiSmsSender

**SmsChannelCircuitBreaker**:
短信渠道健康度故障自愈熔断器，监控通道连续失败率，自动熔断隔离故障渠道并支持半开探测自愈与一键重置。
_Avoid_: ChannelBreaker, SmsFuse, CircuitGuard

**SmsChannelLoadBalancer**:
短信多渠道平滑加权轮询负载均衡器，依托 Nginx 平滑加权轮询算法（SWWR）动态分发虚拟渠道流量。
_Avoid_: ChannelBalancer, SmsRouter, LoadBalanceHandler

**SmsIdempotentGuard**:
业务防重出站幂等防卫卫士，基于业务流水号 bizId 实施时间窗口内的发信防重去重。
_Avoid_: IdempotentChecker, DuplicateFilter, SmsDeduplicator

**SmsSendAuditEvent**:
全链路出站发信审计领域事件，广播每次单条与批量发信的执行终态、MDC 快照、渠道标识与物理耗时。
_Avoid_: SmsLogEvent, AuditNotice, SendTrackEvent

**BaiduSmsProvider**:
百度智能云短信（BCE SMS v3）纯原生驱动实现，基于 BCE-AUTH-V1 HMAC-SHA256 鉴权协议直连官方网关。
_Avoid_: BaiduSmsClient, BceSmsSender

**JdCloudSmsProvider**:
京东云短信纯原生驱动实现，基于 JDCLOUD2-HMAC-SHA256 签名协议直连官方网关。
_Avoid_: JdSmsClient, JdCloudSender

**QiniuSmsProvider**:
七牛云短信纯原生驱动实现，基于 Qiniu AccessToken HMAC-SHA1 鉴权直连官方网关。
_Avoid_: QiniuSmsClient, QiniuSender

**NeteaseSmsProvider**:
网易云信短信纯原生驱动实现，基于 CurTime + Nonce + CheckSum SHA1 校验直连官方网关。
_Avoid_: NeteaseSmsClient, ImSmsSender

**CloopenSmsProvider**:
容联云短信纯原生驱动实现，基于 MD5 SigParameter 与 Base64 Authorization 直连官方 REST API。
_Avoid_: CloopenClient, YuntongxunSender

**ChuanglanSmsProvider**:
创蓝 253 云通讯纯原生驱动实现，支持模板与无模板纯文本正文直发。
_Avoid_: ChuanglanClient, Cl253Sender

**MasSmsProvider**:
中国移动云 MAS 短信纯原生驱动实现，支持 ecName/apId/secretKey 摘要签发与纯文本正文直发。
_Avoid_: MasClient, ChinaMobileMasSender

**UnismsSmsProvider**:
合一短信（UniSMS）纯原生驱动实现，支持 Simple Key 与 HMAC-SHA256 双模验签。
_Avoid_: UniSmsClient, UnimtxSender

**ZhutongSmsProvider**:
助通科技短信纯原生驱动实现，基于 Timestamp + MD5 tKey 协议支持模板、个性化批量、纯文本直发及只读余额探查（`/v2/balance`）。
_Avoid_: ZhutongClient, ZtSmsSender

**SmsChannelProvider**:
动态短信渠道按需供给 SPI 接口，支持多租户在运行时从数据库或配置中心按渠道名热加载 `SmsChannel`。
_Avoid_: ChannelLoader, DynamicChannelSource, SmsAccountProvider

**SmsQuotaGuard**:
单渠道周期发信配额硬熔断门卫（支持 `DAILY` 自然日重置与 `TOTAL` 总量包干），超限立即抛出 `SmsBudgetExceededException`。
_Avoid_: ChannelQuotaManager, SmsAllowanceGuard, QuotaCounter

**SmsSandboxInterceptor**:
非生产环境出站测试安全沙箱拦截器，支持 `log-only` 纯日志空跑、通配符测试号白名单穿透与非白名单号码重定向防误发。
_Avoid_: MockSandboxFilter, DevInterceptFilter, TestPhoneGuard

**SmsPhoneNormalizer**:
国际及港澳台 E.164 手机号归一化工具组件，按渠道 `internationalEnabled` 与 `defaultRegionCode` 统一规范号段格式。
_Avoid_: PhoneFormatUtil, IntlPhoneHelper, MobileNormalizer

**SmsDelayScheduler**:
双模可取消延迟与定时短信调度核心接口（`MEMORY` 受管线程池与 `REDIS` ZSet + Lua 分布式持久化双模），严格履行入队轻校验与到点四维上下文重入防线。
_Avoid_: SmsTimer, DelayQueueManager, SmsJobScheduler

**SmsScheduledReceipt**:
延迟/定时短信调度受理凭证模型，封装唯一任务标识 `taskId`、触发时刻 `triggerAt`、调度状态与渠道名称。
_Avoid_: DelayReceipt, SmsTaskToken, ScheduleAck

**SmsDeliveryQueryResult**:
主动回查单条短信送达状态的标准化契约结果模型，未实现的厂商驱动统一平滑降级返回 `supported=false`。
_Avoid_: DeliveryQueryDTO, SmsStatusVO, QueryReportResult

**SmsBalanceResult**:
只读探查云厂商账户余额或剩余条数的标准化结果模型，探查过程绝对禁止触发任何计费发信动作。
_Avoid_: BalanceDTO, SmsAccountBalanceVO, QuotaProbeResult

**SmsMetrics**:
基于 Micrometer 的短信基础设施低基数度量指标采集器，静态渠道保留原名、动态渠道收敛为 `dynamic`、独立追踪溢出折叠为 `other`，杜绝高基数指标爆炸。
_Avoid_: SmsTelemetry, SmsPromExporter, MetricCollector

**SmsMetricsRecorder**:
短信指标无侵入采集端口接口，在未引入 Micrometer 时提供空实现 `noop()` 平滑降级。
_Avoid_: TelemetryRecorder, MetricsSink

**SmsSendInterceptor**:
短信出站发送链路前后拦截扩展点 SPI（按 `Ordered` 排序），支撑安全沙箱截断、敏感内容合规审查与拓展上报。
_Avoid_: SmsFilter, OutboundHook, SendPlugin

**SmsCallbackVerifier**:
短信 Webhook 入站回调安全校验器 SPI（默认实现为 `DefaultSmsCallbackVerifier`），串联来源 CIDR、恒定时间令牌与厂商签名核验。
_Avoid_: CallbackAuthFilter, WebhookSignatureGuard
