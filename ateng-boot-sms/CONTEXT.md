# SMS Context

`ateng-boot-sms` 负责提供企业级专有多渠道短信发送、验证码全生命周期治理、多命名渠道拓扑、滑动窗口防刷、多维成本熔断、运营商异步回执与四维上下文透传基础设施。

## Language

**SmsTemplate**:
短信操作核心门面具体类，提供命令式纯净 API（`send`、`sendBatch`、`sendAsync`、`sendAfterCommit`）、动态渠道路由、四维跨线程上下文透传及本地事务感知发信。
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
多租户与业务上下文短信渠道动态解析器 SPI，根据当前线程租户或业务标识动态决策目标渠道。
_Avoid_: TenantSmsResolver, ChannelRouter, SmsDispatcher

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
