# Pay 企业级专有多渠道支付与资金底座

面向企业电商收单、SaaS 订阅缴费、政企在线服务及资金清结算的专有支付基础设施，基于微信支付 V3 与支付宝官方权威 SDK 构建，提供双规专有独立门面、顶层轻量统一收单门面、四模自适应证书加载、微信平台证书自动轮换、自动化 Webhook 验签解密、双轨分布式防重锁与全域 BigDecimal 元契约治理。

## Language

**Pay Channel (支付渠道)**:
系统支持的第三方支付底层服务提供商标识（WECHAT_PAY, ALIPAY）。
_Avoid_: 支付类型, 资金通道, 支付方式, PayType

**Merchant (商户 / 应用)**:
在第三方支付平台注册的具有独立资金结算资质与证书密钥的商户号（MchId）或应用（AppId）实体。
_Avoid_: 租户商户, 店铺号, 客户账号

**Out Trade No (商户订单号)**:
商户业务系统内部生成的全局唯一商业交易订单号，对应渠道底层出站交易主键。
_Avoid_: 业务单号, 支付单号, orderId, tradeId

**Transaction ID (渠道交易流水号)**:
第三方支付渠道结算中心返回的全局唯一资金记账交易单号（微信 TransactionId，支付宝 TradeNo）。
_Avoid_: 平台单号, 银行流水号, 第三方单号

**Out Refund No (商户退款单号)**:
商户业务系统内部生成的用于唯一标识单次退款申请的业务凭据单号。
_Avoid_: 退款ID, 售后单号, refundOrderId

**Refund ID (渠道退款流水号)**:
第三方支付渠道受理退款请求后生成的渠道端退款流水单号。
_Avoid_: 平台退款单号, 退款凭据

**Pay Trade Type (交易场景类型)**:
第三方支付平台定义的标准化收单交互终端场景枚举（NATIVE, JSAPI, H5, APP, BARCODE）。
_Avoid_: 支付终端, 支付途径, 交易模式, SceneType

**Pay Trade State (交易状态机)**:
抹平各渠道状态差异的统一标准化交易生命周期状态枚举（SUCCESS, NOTPAY, USERPAYING, CLOSED, REFUND, REVOKED, PAYERROR）。
_Avoid_: 订单状态, 交易结果, 支付标记

**Pay Template (统一支付门面)**:
为常规轻量收单、查单、退款和关单场景提供高直觉统一操作的顶层门面契约。
_Avoid_: UnifiedPayClient, PayService, PayHelper

**Wechat Pay Template (微信支付专有门面)**:
基于官方 wechatpay-java SDK 封装的提供 100% 微信原生能力的专有高阶业务操作门面。
_Avoid_: WxPayService, WxPayClient, WechatPayHelper

**Alipay Template (支付宝专有门面)**:
基于官方 alipay-sdk-java 封装的提供 100% 支付宝原生能力的专有高阶业务操作门面。
_Avoid_: AliPayService, AliPayClient, AlipayHelper

**Pay Callback Listener (回调业务监听器)**:
用于接收框架验签解密并防重拦截后的标准化强类型支付/退款成功事件的业务 SPI 契约。
_Avoid_: PayCallbackConsumer, WebhookHandler, PayEventListener

**Pay Callback Handler (回调处理门面)**:
负责从底层 HTTP 请求提取验签要素、执行公钥验签与 AES-GCM 密文解密的编程式工具门面。
_Avoid_: WebhookParser, SignVerifier, CallbackHelper

**Dual-Track Anti-Replay Lock (双轨防重并发锁)**:
在 Webhook 回调入站时基于交易单号优先获取 Redis 分布式锁并在无 Redis 时以内存并发锁保底的幂等防卫机制。
_Avoid_: 回调锁, 交易幂等锁, 本地锁

**Pay Response (扁平收单凭据响应)**:
统一收单出参模型，同时挂载 Native 二维码、H5 链接、网页表单、微信小程序调起包及 App 调起串的场景直达凭据载体。
_Avoid_: 统一支付响应, 渠道凭证, PayResultDTO

**Auto Certificate Manager (微信平台证书自动轮换器)**:
基于微信官方推荐规范定时从腾讯接口自动下载、校验有效性并在内存中平滑热更新微信支付平台证书的管理器。
_Avoid_: 证书下载器, 密钥同步器, 动态证书服务

**Fund Operation Guard (资金操作安全守卫)**:
针对单笔转账与分账等高危扣除商户真实资金池操作的物理配置总开关与权限拦截防卫机制。
_Avoid_: 资金防护网, 安全切面, 提现拦截器

**Service Provider (服务商 / ISV)**:
具有微信支付或支付宝服务商资质，代表特约商户发起交易收单或管理资金的平台级商户实体。
_Avoid_: 代理商, 平台账号, 合作商户

**Sub-Merchant (特约商户 / 子商户)**:
在服务商模式下挂载于服务商名下进行真实独立资金结算的子门店或入驻商家（微信 sub_mchid）。
_Avoid_: 二级商户, 关联商户, 门店ID

**App Auth Token (第三方应用授权令牌)**:
支付宝开放平台下服务商代表特约商家调用开放接口时必须注入的授权令牌。
_Avoid_: 授权码, 访问令牌, 代理Token

**Secondary Signing (服务端二次加签)**:
微信 JSAPI 或 App 统一下单获取预支付标识后，在服务端使用商户私钥自动计算前端 SDK 调起所需的数字签名与参数打包。
_Avoid_: 客户端加签, 预签名, 二次签名包

**Profit Sharing Unfreeze (完结分账 / 资金解冻)**:
分账执行完毕后将订单在途剩余未分资金从冻结状态彻底释放回商户可用余额的闭环操作。
_Avoid_: 释放分账, 结束分账, 解冻余额

**Partial Refund (部分退款)**:
针对同一笔订单多次发起非全额退款并通过独立退款单号与原单总额保证严格幂等的售后操作。
_Avoid_: 分次退款, 局部退款, 差额退款

**Pay Bill Entry (对账单明细条目)**:
从第三方支付渠道下载并解析后的标准化单笔交易或退款资金对账记录实体。
_Avoid_: 账单明细, 对账流水, 财务账目

**Pay Bill Parser (对账单流式解析器)**:
自动完成渠道 GZIP/ZIP 压缩包解压并流式将 CSV 文本转译为强类型明细与汇总实体的解析引擎。
_Avoid_: 账单转换器, 对账解压器, CSV解析工具

**Barcode Pay Executor (条码付收银执行器)**:
封装当面付付款码被扫、`USERPAYING` 状态阶梯轮询与超时自动主动撤销冲正的线下收银台执行器。
_Avoid_: 条码处理器, 刷卡执行器, 轮询管理器

**Trade Cancel (交易撤销 / 冲正)**:
针对条码付或当面付未支付或超时订单，由商户主动向渠道发起的解除支付授权并对已扣款项自动回退的最终性安全冲正操作。
_Avoid_: 订单取消, 关单, 撤回交易
