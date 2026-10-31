# ateng-boot-pay 基础设施 Starter

> 工业级企业多渠道专有支付中间件底座 Starter，深度集成腾讯官方权威 `wechatpay-java:0.2.17` 与蚂蚁金服 `alipay-sdk-java:4.40.1044.ALL` 原生 SDK，支持双轨专有独立门面 `WechatPayTemplate` 与 `AlipayTemplate`、顶层轻量统一收单门面 `PayTemplate`、支付宝小程序原生调起、微信平台公钥双模自适应验签、资金售后退款、单笔转账到零钱/账户、订单资金分账解冻、线下收银台条码付自动轮询与冲正退避重试、财务对账单流式解压解析引擎、四模密钥自适应解析加载、微信平台证书自动轮换与平滑衰退引擎、双轨异步 Webhook 自动化验签解密、双模防重并发排队锁、强类型业务回调监听器 SPI 与全域 BigDecimal 元契约治理。

- **架构基线**：[ADR-0048 企业级多渠道专有支付中间件底座架构设计](../docs/adr/0048-enterprise-multi-channel-payment-architecture.md)、[ADR-0075 企业级支付底座官方SDK权威对齐与全场景加固架构](../docs/adr/0075-enterprise-pay-official-alignment-and-full-scenario-hardening-architecture.md)
- **研发工单闭环**：[#552](https://github.com/atengk/ateng-boot/issues/552)（Parent 目标架构工单）、[#553](https://github.com/atengk/ateng-boot/issues/553)（微信平台公钥加载与自适应验签）、[#554](https://github.com/atengk/ateng-boot/issues/554)（微信支付 V3 线下条码付撤销冲正与受控退避重试）、[#555](https://github.com/atengk/ateng-boot/issues/555)（支付宝小程序统一收单创建与买家标识智能推导）、[#556](https://github.com/atengk/ateng-boot/issues/556)（Sample 样例工程双模驱动升级与 22 项全链路场景实测闭环）、[#557](https://github.com/atengk/ateng-boot/issues/557)（五维生产交付文档）

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

### 0.1 核心执行门面与业务服务

| 特性分类 | 特性名称 | 核心门面 / SPI / 工具类 | 关键配置项 (`ateng.boot.pay.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **统一收单** | 统一多渠道收单路由 | `PayTemplate` | `ateng.boot.pay.enabled` | `pay(UnifiedPayRequest)`，多渠道自动路由，输出扁平凭据 `PayResponse`（`qrCode` / `payUrl` / `formHtml` / `wechatJsapi` / `tradeNo` / `orderString`） | `PayTemplateTest` / `PaySampleIntegrationTest` |
| **统一收单** | 统一交易状态查询 | `PayTemplate` | `ateng.boot.pay.enabled` | `queryOrder(UnifiedQueryRequest)` / `queryOrder(PayChannel, String)` / `queryOrder(PayChannel, String, String)`，主动查询订单生命周期状态 | `PayTemplateTest` / `PaySampleIntegrationTest` |
| **统一收单** | 统一未付款订单关闭 | `PayTemplate` | `ateng.boot.pay.enabled` | `closeOrder(UnifiedCloseRequest)` / `closeOrder(PayChannel, String)` / `closeOrder(PayChannel, String, String)`，主动关闭未付款超时订单 | `PayTemplateTest` / `PaySampleIntegrationTest` |
| **统一收单** | 统一交易订单撤销/冲正 | `PayTemplate` | `ateng.boot.pay.enabled` | `cancelOrder(UnifiedCancelRequest)` / `cancelOrder(PayChannel, String)` / `cancelOrder(PayChannel, String, String)`，超时未决撤销与关单冲正 | `PayTemplateTest` |
| **统一退款** | 统一售后退款发起 | `PayTemplate` | `ateng.boot.pay.enabled` | `refund(UnifiedRefundRequest)`，支持原路全额与部分多次退款，输出统一退款凭据 `PayRefundResponse` | `PayTemplateTest` / `PaySampleIntegrationTest` |
| **统一退款** | 统一退款状态查询 | `PayTemplate` | `ateng.boot.pay.enabled` | `queryRefund(UnifiedRefundQueryRequest)` / `queryRefund(PayChannel, String)` / `queryRefund(PayChannel, String, String)`，查询退款进度与入账流水 | `PayTemplateTest` / `PaySampleIntegrationTest` |
| **统一转账** | 统一单笔资金转账发起 | `PayTemplate` | `ateng.boot.pay.transfer.enabled` | `transfer(UnifiedTransferRequest)`，商家转账到微信零钱与支付宝单笔转账，输出 `PayTransferResponse` | `PayTemplateTest` / `PaySampleIntegrationTest` |
| **统一转账** | 统一单笔转账状态查询 | `PayTemplate` | `ateng.boot.pay.transfer.enabled` | `queryTransfer(UnifiedTransferQueryRequest)` / `queryTransfer(PayChannel, String)` / `queryTransfer(PayChannel, String, String)`，查询转账到账状态 | `PayTemplateTest` / `PaySampleIntegrationTest` |
| **转账业务** | 单笔资金转账专有服务 | `PayTransferService` | `ateng.boot.pay.transfer.enabled` | `transfer(...)` / `queryTransfer(...)` / `transferWechat(...)` / `queryWechatTransfer(...)`，高敏单笔转账专有业务服务 | `PayTransferServiceTest` / `PaySampleIntegrationTest` |
| **对账单流** | 财务对账单流式下载 | `PayTemplate` | `ateng.boot.pay.enabled` | `downloadBillStream(PayBillDownloadRequest)` / `downloadBill(PayBillDownloadRequest)`，流式下载渠道官方明细对账单输入流 | `PayTemplateTest` / `PaySampleIntegrationTest` |
| **统一逃生舱** | 便捷原生客户端逃生舱 | `PayTemplate` | - | `getWechatClient()` 获取微信 `HttpClient`；`getAlipayClient()` 获取支付宝 `AlipayClient`；`getWechatPayTemplate()` 与 `getAlipayTemplate()` 获取独立专有门面 | `PayTemplateTest` |
| **条码收银** | 线下付款码收银轮询执行器 | `BarcodePayExecutor` | `ateng.boot.pay.barcode.max-wait-time`<br>`ateng.boot.pay.barcode.poll-interval`<br>`ateng.boot.pay.barcode.auto-cancel-on-timeout` | `execute(UnifiedPayRequest)` / `execute(UnifiedPayRequest, Duration, Duration)` / `execute(UnifiedPayRequest, Duration, Duration, boolean)`，阶梯式轮询支付结果，超时自动撤销冲正 | `BarcodePayExecutorTest` / `PaySampleIntegrationTest` |
| **账单解析** | 财务对账单流式解压解析引擎 | `PayBillParser` | - | `parse(InputStream)` 全量内存解析为 `PayBillResult`；`parse(InputStream, Consumer<PayBillEntry>)` 零 OOM 流式逐行解压与回调 | `PayBillParserTest` / `PaySampleIntegrationTest` |
| **微信专有** | Native 扫码收单 | `WechatPayTemplate` | `ateng.boot.pay.wechat.enabled`<br>`ateng.boot.pay.wechat.merchants.<key>.*` | `payNative(WechatPayRequest)`，直连商户与特约服务商扫码预下单，返回 `qrCode` 二维码内容链接 | `WechatPayTemplateTest` / `PaySampleIntegrationTest` |
| **微信专有** | JSAPI 小程序/公众号支付 | `WechatPayTemplate` | `ateng.boot.pay.wechat.merchants.<key>.app-id`<br>`ateng.boot.pay.wechat.merchants.<key>.private-key-path` | `payJsapi(WechatPayRequest)`，服务端全自动 RSA 二次加签，封包 5 元素直达凭据 `wechatJsapi` | `WechatPayTemplateTest` / `PaySampleIntegrationTest` |
| **微信专有** | H5 移动网页支付 | `WechatPayTemplate` | `ateng.boot.pay.wechat.merchants.<key>.*` | `payH5(WechatPayRequest)`，生成手机浏览器专属跳转支付链接 `payUrl`（`h5_url`） | `WechatPayTemplateTest` / `PaySampleIntegrationTest` |
| **微信专有** | App 移动客户端支付 | `WechatPayTemplate` | `ateng.boot.pay.wechat.merchants.<key>.*` | `payApp(WechatPayRequest)`，服务端计算开放平台 App SDK 调起签名，封包 `wechatApp` 凭据 | `WechatPayTemplateTest` / `PaySampleIntegrationTest` |
| **微信专有** | 条码付 / 付款码被扫 | `WechatPayTemplate` | `ateng.boot.pay.wechat.merchants.<key>.*` | `payBarcode(WechatPayRequest)`，线下商户扫描用户微信付款码收单（`auth_code`） | `WechatPayTemplateTest` |
| **微信专有** | 微信订单状态查询 | `WechatPayTemplate` | `ateng.boot.pay.wechat.merchants.<key>.*` | `queryOrder(WechatPayRequest)` / `queryOrder(String)` / `queryOrder(String, String)`，微信订单状态机自动映射 | `WechatPayTemplateTest` |
| **微信专有** | 微信未支付订单关闭 | `WechatPayTemplate` | `ateng.boot.pay.wechat.merchants.<key>.*` | `closeOrder(WechatPayRequest)` / `closeOrder(String)` / `closeOrder(String, String)`，关闭超时未支付会话 | `WechatPayTemplateTest` |
| **微信专有** | 微信条码付撤销冲正与重试 | `WechatPayTemplate` | `ateng.boot.pay.wechat.merchants.<key>.*` | `cancelOrder(WechatPayRequest)` / `cancelOrder(String)` / `cancelOrder(String, String)`，调用 `/reverse` 接口并在遇到 `SYSTEMERROR` 时自动受控退避重试 | `WechatPayTemplateTest` |
| **微信专有** | 微信售后退款发起与查询 | `WechatPayTemplate` | `ateng.boot.pay.wechat.merchants.<key>.*` | `refund(WechatRefundRequest)` 发起退款；`queryRefund(WechatRefundRequest)` / `queryRefund(String)` / `queryRefund(String, String)` 查询微信退款状态 | `WechatPayTemplateTest` |
| **微信专有** | 微信官方对账单下载 | `WechatPayTemplate` | `ateng.boot.pay.wechat.merchants.<key>.*` | `downloadTradeBill(...)` 下载交易账单；`downloadFundFlowBill(...)` 下载资金账单，自动校验 SHA1 摘要并返回流 | `WechatPayTemplateTest` |
| **微信分账** | 微信订单资金分账服务 | `WechatProfitSharingService` | `ateng.boot.pay.profit-sharing.enabled` | 封装 `createOrder` 请求分账、`queryOrder` 查询分账、`unfreezeOrder` 完结解冻在途资金、`addReceiver`/`deleteReceiver` 接收方管理、`createReturnOrder` 分账回退 | `WechatProfitSharingServiceTest` / `PaySampleIntegrationTest` |
| **微信公钥** | 平台公钥直接验签引擎 | `WechatPayTemplate` | `ateng.boot.pay.wechat.merchants.<key>.wechat-pay-public-key-path`<br>`ateng.boot.pay.wechat.merchants.<key>.wechat-pay-public-key-id` | 加载微信支付官方静态平台公钥，直接构建 `RSAPublicKeyConfig` 实例，免除周期性下载证书网络交互开销 | `WechatPayTemplateTest` |
| **微信证书** | 平台证书自动轮换管理 | `WechatAutoCertificateManager` | `ateng.boot.pay.wechat.merchants.<key>.auto-certificate` | 微信商户平台证书后台守护自动轮换、内存平滑切换与历史证书防雪崩衰退管理 | `WechatAutoCertificateManagerTest` |
| **微信逃生舱** | 原生客户端与服务持有获取 | `WechatPayTemplate` | - | `getClient(String)` / `getClient()` / `getHttpClient()` 获取官方 `HttpClient`；`getWechatConfig(String)` 获取官方 `Config`；`setCustomHttpClient(...)` 注入测试拦截器 | `WechatPayTemplateTest` |
| **支付宝专有** | 电脑网站支付 (Page) | `AlipayTemplate` | `ateng.boot.pay.alipay.enabled`<br>`ateng.boot.pay.alipay.apps.<key>.*` | `payPage(AlipayPayRequest)`，PC 端收银台，返回 `formHtml` 包含自提交 HTML 表单与脚本 | `AlipayTemplateTest` |
| **支付宝专有** | 手机网站支付 (Wap) | `AlipayTemplate` | `ateng.boot.pay.alipay.apps.<key>.*` | `payWap(AlipayPayRequest)`，手机浏览器收银台，返回 `payUrl` 跳转链接或 `formHtml` | `AlipayTemplateTest` |
| **支付宝专有** | App 移动客户端支付 | `AlipayTemplate` | `ateng.boot.pay.alipay.apps.<key>.*` | `payApp(AlipayPayRequest)`，生成支付宝官方客户端 SDK 加签调起串 `orderString` | `AlipayTemplateTest` / `PaySampleIntegrationTest` |
| **支付宝专有** | 小程序统一收单创建 (JSAPI) | `AlipayTemplate` | `ateng.boot.pay.alipay.apps.<key>.*` | `payJsapi(AlipayPayRequest)`，调用 `alipay.trade.create`，智能推导 `buyer_id` / `buyer_open_id`，输出 `tradeNo` 供小程序 `my.tradePay` 调起 | `AlipayTemplateTest` / `PaySampleIntegrationTest` |
| **支付宝专有** | 当面付扫码预下单 (Qr) | `AlipayTemplate` | `ateng.boot.pay.alipay.apps.<key>.*` | `payQr(AlipayPayRequest)`，线下收单生成支付宝付款二维码内容链接 `qrCode` | `AlipayTemplateTest` |
| **支付宝专有** | 当面付条码收单 (Barcode) | `AlipayTemplate` | `ateng.boot.pay.alipay.apps.<key>.*` | `payBarcode(AlipayPayRequest)`，商户扫描用户支付宝付款码收单（`auth_code`） | `AlipayTemplateTest` |
| **支付宝专有** | 支付宝订单状态查询 | `AlipayTemplate` | `ateng.boot.pay.alipay.apps.<key>.*` | `queryOrder(AlipayPayRequest)` / `queryOrder(String)` / `queryOrder(String, String)`，查询支付宝收单流水 | `AlipayTemplateTest` |
| **支付宝专有** | 支付宝未支付订单关闭 | `AlipayTemplate` | `ateng.boot.pay.alipay.apps.<key>.*` | `closeOrder(AlipayPayRequest)` / `closeOrder(String)` / `closeOrder(String, String)`，关闭超时订单 | `AlipayTemplateTest` |
| **支付宝专有** | 支付宝当面付订单撤销冲正 | `AlipayTemplate` | `ateng.boot.pay.alipay.apps.<key>.*` | `cancelOrder(AlipayPayRequest)` / `cancelOrder(String)` / `cancelOrder(String, String)`，调用 `alipay.trade.cancel` 撤销超时交易并自动冲正 | `AlipayTemplateTest` |
| **支付宝专有** | 支付宝售后退款与查询 | `AlipayTemplate` | `ateng.boot.pay.alipay.apps.<key>.*` | `refund(AlipayRefundRequest)` 支持部分退款与分账同步退款；`queryRefund(AlipayRefundRequest)` / `queryRefund(String, String)` 查询退款明细 | `AlipayTemplateTest` |
| **支付宝专有** | 支付宝账单下载链接查询 | `AlipayTemplate` | `ateng.boot.pay.alipay.apps.<key>.*` | `downloadBillUrl(String, String)` / `downloadBillStream(...)`，获取官方账单临时下载 URL 并流式拉取 | `AlipayTemplateTest` |
| **支付宝逃生舱** | 原生客户端与缓存清理获取 | `AlipayTemplate` | - | `getClient(String)` / `getClient()` / `getAlipayClient()` 获取蚂蚁金服官方原生 `AlipayClient`；`clearClientCache()` 运行时重置客户端缓存 | `AlipayTemplateTest` |
| **回调网关** | 统一 Webhook 端点 | `PayWebhookController` | `ateng.boot.pay.callback.enabled`<br>`ateng.boot.pay.callback.path-prefix` | 暴露统一端点 `/ateng-pay/callback/{channel}/{merchantId}`，自动拦截、验签、解密与并发防重 | `PayWebhookControllerTest` / `PaySampleIntegrationTest` |
| **回调处理** | 编程式回调处理门面 | `PayCallbackHandler` | `ateng.boot.pay.callback.idempotent-timeout` | `handleWechat(...)` 与 `handleAlipay(...)`，支持 Servlet 请求或解耦参数对象编程式调度 | `PayWebhookControllerTest` / `PaySampleIntegrationTest` |
| **回调监听 SPI** | 支付与退款异步监听器 | `PayCallbackListener` | - | 实现 `onPaymentSuccess(PaymentSuccessCallback)` 与 `onRefundSuccess(RefundSuccessCallback)`，返回确定性 `PayCallbackResult` | `OrderPaymentCallbackListener` / `PaySampleIntegrationTest` |
| **证书扩展 SPI** | 外部密钥证书提供器 | `PayCertificateProvider` | - | 实现外部 KMS / HashiCorp Vault / 数据库动态解密私钥与证书扩展接口 | `PayKeyLoaderTest` |
| **多商户注册** | 命名多商户多应用管理 | `NamedMerchantRegistry` | `ateng.boot.pay.wechat.merchants.*`<br>`ateng.boot.pay.alipay.apps.*` | 运行时动态注册与热更新商户/应用配置，支持三级降级查找（显式键 -> 默认键 -> 首个已注册） | `NamedMerchantRegistryTest` |
| **线程切源** | 线程级动态切源持有者 | `PayContextHolder` | - | `setMerchantKey(String)` / `getMerchantKey()` / `clear()`，支持多租户与请求上下文动态绑定商户别名 | `PayContextHolderTest` |
| **密钥解析** | 四模自适应密钥加载器 | `PayKeyLoader` | - | 自动识别 `classpath:`、`file:`、纯文本 PEM（`loadPrivateKeyPem` / `loadPublicKeyPem`）与纯 Base64 编码，静态平台公钥缓存与动态清理 | `PayKeyLoaderTest` |
| **防重并发锁** | 双轨分布式防重锁门面 | `PayLockManager` | `ateng.boot.pay.callback.idempotent-timeout` | `tryLock(String, Duration)` 与 `unlock(String)`，Redis 基础设施存在时优先装配分布式锁，无 Redis 内存锁保底 | `InMemoryPayLockManagerTest` / `RedisPayLockManagerTest` |
| **货币工具** | 元/分无损高精度换算 | `CurrencyUtil` | - | `yuanToFen(BigDecimal)` 与 `fenToYuan(Long)` / `fenToYuan(Integer)`，防御浮点精度失真 | `CurrencyUtilTest` |

---

### 0.2 核心强类型枚举一览

| 枚举类型 | 常用枚举项 | 语义说明 |
| :--- | :--- | :--- |
| `PayChannel` | `WECHAT_PAY`、`ALIPAY` | 支付渠道（微信支付 / 支付宝开放平台） |
| `PayTradeType` | `NATIVE`、`JSAPI`、`H5`、`APP`、`BARCODE`、`PAGE`、`WAP` | 统一交易类型（扫码、小程序/公众号、手机网页、移动应用、付款码收银、电脑网站、手机网站） |
| `PayTradeState` | `SUCCESS`、`USERPAYING`、`NOTPAY`、`CLOSED`、`REVOKED`、`PAYERROR` | 统一交易生命周期状态机 |
| `PayRefundState` | `SUCCESS`、`PROCESSING`、`ABNORMAL`、`CLOSED` | 统一资金售后退款状态机 |
| `PayTransferState`| `SUCCESS`、`FAIL`、`PROCESSING`、`REFUND`、`CLOSED` | 统一单笔资金转账状态机 |
| `PayResultCode` | `PAY_SUCCESS` (200)、`PAY_PARAM_INVALID` (58001)、`PAY_FEATURE_DISABLED` (58002)、`PAY_SIGN_FAILED` (58003)、`PAY_GATEWAY_ERROR` (58007) 等 | 支付业务语义化状态错误码 |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-pay` 是面向企业电商收单、SaaS 订阅缴费、政企在线支付与资金清结算的生产就绪专有支付基础设施：
- **纯净依赖与双轨权威 SDK**：核心网络交互与协议解密 100% 对齐腾讯官方权威 `wechatpay-java:0.2.17` 与蚂蚁金服 `alipay-sdk-java:4.40.1044.ALL`，杜绝任何第三方未经安全审计的二次封装工具包；
- **全场景统一收单门面与扁平直达凭据**：统一轻量收单门面 `PayTemplate` 屏蔽微信与支付宝异构协议差异，出参采用扁平化统一凭据 `PayResponse`（前端按需直接取用 `qrCode`、`payUrl`、`formHtml`、`wechatJsapi`、`tradeNo`、`orderString`），彻底消除传统多态强转与 `ClassCastException` 隐患；
- **传输层与持久层绝对隔离 (Zero-Entity-Leakage)**：Controller 传输边界 100% 严禁暴露 ORM 实体，统一使用入参 DTO 挂载 JSR-303 格式校验与出参强类型 `Result<T>`（严格恪守 [ADR-0002](../docs/adr/0002-explicit-result-contract-no-response-body-advice.md) 与 [ADR-0010](../docs/adr/0010-transport-pagination-contract-decoupling.md)）；
- **全域强类型与全自动二次加签**：针对微信小程序/公众号 JSAPI 统一下单，全自动在服务端使用商户私钥计算 RSA 数字签名并封包 5 元素调起凭据（`appId`、`timeStamp`、`nonceStr`、`package`、`signType`、`paySign`），前端一行代码即可直接调用 `wx.requestPayment`；针对支付宝小程序，自动调用 `alipay.trade.create` 并输出 `tradeNo` 供前端 `my.tradePay` 调起；
- **双轨异步 Webhook 网关与并发防重排队锁**：统一映射端点 `/ateng-pay/callback/{channel}/{merchantId}`，自动拦截、验签、解密入站通知，结合 `PayLockManager`（Redis 优先 + 本地内存保底）实现 10 秒并发防重排队，业务只关注实现 `PayCallbackListener` SPI；
- **全域资金售后与分账转账治理**：完备覆盖原路退款、单笔转账到零钱/账户、分账接收方管理与剩余资金完结解冻、线下收银台轮询防超扣冲正与自动受控退避重试，以及财务对账单流式解压解析。

### Maven 坐标引入

在已导入 `ateng-boot-bom` 的工程中，无需声明版本号即可直接引入：

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-pay</artifactId>
</dependency>
```

---

## 2. 生产基础配置与极简起步体验 (Happy Path & Out-of-the-Box)

### 2.1 生产标准配置范式 (`application.yml`)

遵循 [ADR-0028](../docs/adr/0028-samples-modularization-and-incremental-integration-testing.md) 与 [ADR-0075](../docs/adr/0075-enterprise-pay-official-alignment-and-full-scenario-hardening-architecture.md)，配置支持 `optional:classpath:application-local.yml` 动态探测，保护生产凭据：

```yaml
server:
  port: 8080

spring:
  application:
    name: ateng-boot-sample-pay
  config:
    import: "optional:classpath:application-local.yml"

ateng:
  boot:
    pay:
      enabled: true
      # 异步 Webhook 回调网关与并发防重排队配置
      callback:
        enabled: true
        path-prefix: /ateng-pay/callback
        idempotent-timeout: 10s
      # 单笔资金转账高敏开关
      transfer:
        enabled: true
      # 微信资金分账高敏开关
      profit-sharing:
        enabled: true
      # 线下收银台条码支付轮询与冲正配置
      barcode:
        max-wait-time: 30s
        poll-interval: 2s
        auto-cancel-on-timeout: true
      # 微信支付官方 V3 沙箱/生产配置
      wechat:
        enabled: true
        default-merchant: default
        merchants:
          default:
            app-id: wx8888888888888888
            mch-id: "1900000001"
            api-v3-key: "32bytesMockApiV3KeyForTesting000"
            merchant-serial-number: "1234567890ABCDEF1234567890ABCDEF"
            # 支持 classpath: / file: / PEM 文本
            private-key-path: classpath:certs/test_private_key.pem
            # 模式 A: 微信官方新版平台公钥直连 (推荐，免下载证书)
            wechat-pay-public-key-path: classpath:certs/test_public_key.pem
            wechat-pay-public-key-id: "PUB_KEY_ID_TEST_001"
            # 模式 B: 微信平台证书自动轮换守护
            auto-certificate: false
            notify-url: "http://127.0.0.1:8080/ateng-pay/callback/wechat"
      # 支付宝官方开放平台沙箱/生产配置
      alipay:
        enabled: true
        default-app: default
        apps:
          default:
            app-id: "2021000000000001"
            private-key-path: classpath:certs/test_private_key.pem
            alipay-public-key: classpath:certs/test_public_key.pem
            server-url: "https://openapi-sandbox.dl.alipaydev.com/gateway.do"
            notify-url: "http://127.0.0.1:8080/ateng-pay/callback/alipay"
            return-url: "http://127.0.0.1:8080/pay/return"
```

---

### 2.2 统一多渠道收单（Native 扫码、JSAPI 与支付宝小程序）

在业务控制器中统一注入 `PayTemplate` 门面：

```java
@Slf4j
@Validated
@RestController
@RequestMapping("/pay/checkout")
public class PayCheckoutController {

    private final PayTemplate payTemplate;

    public PayCheckoutController(PayTemplate payTemplate) {
        this.payTemplate = Objects.requireNonNull(payTemplate, "PayTemplate 不能为空");
    }

    /**
     * 1. Native 扫码预下单，返回 qrCode 供前端直接渲染二维码
     */
    @PostMapping("/native")
    public Result<PayResponse> checkoutNative(@Valid @RequestBody PayCheckoutDTO dto) {
        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(dto.getChannel())
                .tradeType(PayTradeType.NATIVE)
                .outTradeNo(dto.getOutTradeNo())
                .amount(dto.getAmount())
                .subject(dto.getSubject())
                .merchantKey(dto.getMerchantKey())
                .build();

        PayResponse response = payTemplate.pay(request);
        return Result.success(response);
    }

    /**
     * 2. JSAPI 调起收单 (微信小程序/公众号生成服务端二次加签 5 元素调起包；支付宝小程序生成 tradeNo)
     */
    @PostMapping("/jsapi")
    public Result<PayResponse> checkoutJsapi(@Valid @RequestBody PayCheckoutDTO dto) {
        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(dto.getChannel())
                .tradeType(PayTradeType.JSAPI)
                .outTradeNo(dto.getOutTradeNo())
                .amount(dto.getAmount())
                .subject(dto.getSubject())
                .openid(dto.getOpenid())
                .buyerId(dto.getBuyerId())
                .buyerOpenId(dto.getBuyerOpenId())
                .merchantKey(dto.getMerchantKey())
                .build();

        PayResponse response = payTemplate.pay(request);
        return Result.success(response);
    }

    /**
     * 3. 支付宝小程序专有收单（生成 tradeNo 凭据，前端直接传给 my.tradePay）
     */
    @PostMapping("/alipay-miniapp")
    public Result<PayResponse> checkoutAlipayMiniApp(@Valid @RequestBody PayCheckoutDTO dto) {
        dto.setChannel(PayChannel.ALIPAY);
        return checkoutJsapi(dto);
    }
}
```

#### 微信 JSAPI 扁平直达响应示例

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "channel": "WECHAT_PAY",
    "tradeType": "JSAPI",
    "outTradeNo": "ORD_JSAPI_20261023_002",
    "amount": 12.50,
    "success": true,
    "wechatJsapi": {
      "appId": "wx8888888888888888",
      "timeStamp": "1793453980",
      "nonceStr": "mock_nonce_12345",
      "packageVal": "prepay_id=wx20261023prepay001",
      "signType": "RSA",
      "paySign": "MOCK_SIGNATURE_BASE64_STRING=="
    }
  },
  "success": true,
  "traceId": "f9596239558e139a6a987a6293d957e9"
}
```

#### 支付宝小程序响应示例

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "channel": "ALIPAY",
    "tradeType": "JSAPI",
    "outTradeNo": "ORD_ALI_MINI_001",
    "amount": 88.00,
    "success": true,
    "tradeNo": "202610232200140099",
    "transactionId": "202610232200140099"
  },
  "success": true,
  "traceId": "9b12a34fc890ef01"
}
```

---

### 2.3 线下条码收银台同步轮询扣款与超时撤销冲正

线下当面收银使用 `BarcodePayExecutor` 自动处理用户输入密码轮询，支持阶梯等待与自动冲正：

```java
@Slf4j
@Validated
@RestController
@RequestMapping("/pay/barcode")
public class PayBarcodeController {

    private final BarcodePayExecutor barcodePayExecutor;

    public PayBarcodeController(BarcodePayExecutor barcodePayExecutor) {
        this.barcodePayExecutor = Objects.requireNonNull(barcodePayExecutor, "BarcodePayExecutor 不能为空");
    }

    @PostMapping("/cashier")
    public Result<PayResponse> cashierPay(@Valid @RequestBody PayBarcodeDTO dto) {
        UnifiedPayRequest request = UnifiedPayRequest.builder()
                .channel(dto.getChannel())
                .tradeType(PayTradeType.BARCODE)
                .merchantKey(dto.getMerchantKey())
                .outTradeNo(dto.getOutTradeNo())
                .amount(dto.getAmount())
                .authCode(dto.getAuthCode())
                .subject(dto.getSubject())
                .build();

        PayResponse response;
        if (dto.getMaxWaitTime() != null && dto.getPollInterval() != null) {
            boolean autoCancel = dto.getAutoCancelOnTimeout() != null ? dto.getAutoCancelOnTimeout() : true;
            response = barcodePayExecutor.execute(request, dto.getMaxWaitTime(), dto.getPollInterval(), autoCancel);
        } else {
            response = barcodePayExecutor.execute(request);
        }
        return Result.success(response);
    }
}
```

---

### 2.4 售后退款申请与状态查询

```java
@RestController
@RequestMapping("/pay/refund")
public class PayRefundController {

    private final PayTemplate payTemplate;

    public PayRefundController(PayTemplate payTemplate) {
        this.payTemplate = Objects.requireNonNull(payTemplate, "PayTemplate 不能为空");
    }

    @PostMapping("/apply")
    public Result<PayRefundResponse> applyRefund(@Valid @RequestBody PayRefundDTO dto) {
        UnifiedRefundRequest request = UnifiedRefundRequest.builder()
                .channel(dto.getChannel())
                .merchantKey(dto.getMerchantKey())
                .outTradeNo(dto.getOutTradeNo())
                .transactionId(dto.getTransactionId())
                .outRefundNo(dto.getOutRefundNo())
                .refundAmount(dto.getRefundAmount())
                .totalAmount(dto.getTotalAmount())
                .reason(dto.getReason())
                .build();

        PayRefundResponse response = payTemplate.refund(request);
        return Result.success(response);
    }

    @PostMapping("/query")
    public Result<PayRefundResponse> queryRefund(@Valid @RequestBody PayRefundQueryDTO dto) {
        UnifiedRefundQueryRequest request = UnifiedRefundQueryRequest.builder()
                .channel(dto.getChannel())
                .merchantKey(dto.getMerchantKey())
                .outTradeNo(dto.getOutTradeNo())
                .outRefundNo(dto.getOutRefundNo())
                .build();

        PayRefundResponse response = payTemplate.queryRefund(request);
        return Result.success(response);
    }
}
```

---

### 2.5 单笔资金转账申请与到账查询

```java
@RestController
@RequestMapping("/pay/transfer")
public class PayTransferController {

    private final PayTemplate payTemplate;

    public PayTransferController(PayTemplate payTemplate) {
        this.payTemplate = Objects.requireNonNull(payTemplate, "PayTemplate 不能为空");
    }

    @PostMapping("/apply")
    public Result<PayTransferResponse> applyTransfer(@Valid @RequestBody PayTransferDTO dto) {
        UnifiedTransferRequest request = UnifiedTransferRequest.builder()
                .channel(dto.getChannel())
                .merchantKey(dto.getMerchantKey())
                .outBizNo(dto.getOutBizNo())
                .amount(dto.getAmount())
                .title(dto.getTitle())
                .payeeAccount(dto.getPayeeAccount())
                .payeeRealName(dto.getPayeeRealName())
                .build();

        PayTransferResponse response = payTemplate.transfer(request);
        return Result.success(response);
    }

    @PostMapping("/query")
    public Result<PayTransferResponse> queryTransfer(@Valid @RequestBody PayTransferQueryDTO dto) {
        UnifiedTransferQueryRequest request = UnifiedTransferQueryRequest.builder()
                .channel(dto.getChannel())
                .merchantKey(dto.getMerchantKey())
                .outBizNo(dto.getOutBizNo())
                .orderId(dto.getOrderId())
                .build();

        PayTransferResponse response = payTemplate.queryTransfer(request);
        return Result.success(response);
    }
}
```

---

### 2.6 微信资金分账接收方管理与剩余资金完结解冻

```java
@RestController
@RequestMapping("/pay/profit-sharing")
public class PayProfitSharingController {

    private final WechatProfitSharingService profitSharingService;

    public PayProfitSharingController(ObjectProvider<WechatProfitSharingService> provider) {
        this.profitSharingService = provider.getIfAvailable();
    }

    @PostMapping("/receiver/add")
    public Result<AddReceiverResponse> addReceiver(@Valid @RequestBody ProfitSharingReceiverDTO dto) {
        if (profitSharingService == null) {
            return Result.failure("微信资金分账特性未启用");
        }
        AddReceiverRequest request = new AddReceiverRequest();
        request.setType(ReceiverType.valueOf(dto.getType().toUpperCase()));
        request.setAccount(dto.getAccount());
        request.setRelationType(ReceiverRelationType.valueOf(dto.getRelationType().toUpperCase()));

        AddReceiverResponse response = profitSharingService.addReceiver(dto.getMerchantKey(), request);
        return Result.success(response);
    }

    @PostMapping("/order/unfreeze")
    public Result<OrdersEntity> unfreezeOrder(@Valid @RequestBody ProfitSharingUnfreezeDTO dto) {
        if (profitSharingService == null) {
            return Result.failure("微信资金分账特性未启用");
        }
        UnfreezeOrderRequest request = new UnfreezeOrderRequest();
        request.setOutOrderNo(dto.getOutOrderNo());
        request.setTransactionId(dto.getTransactionId());
        request.setDescription(dto.getDescription());

        OrdersEntity entity = profitSharingService.unfreezeOrder(dto.getMerchantKey(), request);
        return Result.success(entity);
    }
}
```

---

### 2.7 财务对账单流式下载与双模解析

```java
@RestController
@RequestMapping("/pay/bill")
public class PayBillController {

    private final PayTemplate payTemplate;
    private final PayBillParser payBillParser;

    public PayBillController(PayTemplate payTemplate, PayBillParser payBillParser) {
        this.payTemplate = Objects.requireNonNull(payTemplate, "PayTemplate 不能为空");
        this.payBillParser = Objects.requireNonNull(payBillParser, "PayBillParser 不能为空");
    }

    /**
     * 模式一：全量内存模式，下载并解析出包含所有明细与汇总的 PayBillResult
     */
    @PostMapping("/parse-memory")
    public Result<PayBillResult> parseMemory(@Valid @RequestBody PayBillQueryDTO dto) throws Exception {
        PayBillDownloadRequest request = PayBillDownloadRequest.builder()
                .channel(dto.getChannel())
                .merchantKey(dto.getMerchantKey())
                .billDate(dto.getBillDate())
                .billType(dto.getBillType())
                .tarType(dto.getTarType())
                .build();

        try (InputStream stream = payTemplate.downloadBillStream(request)) {
            PayBillResult result = payBillParser.parse(stream);
            return Result.success(result);
        }
    }

    /**
     * 模式二：零 OOM 模式，边流式解压边逐行回调消费，常驻 O(1) 内存
     */
    @PostMapping("/stream-reconcile")
    public Result<PayBillSummary> streamReconcile(@Valid @RequestBody PayBillQueryDTO dto) throws Exception {
        PayBillDownloadRequest request = PayBillDownloadRequest.builder()
                .channel(dto.getChannel())
                .merchantKey(dto.getMerchantKey())
                .billDate(dto.getBillDate())
                .billType(dto.getBillType())
                .tarType(dto.getTarType())
                .build();

        try (InputStream stream = payTemplate.downloadBillStream(request)) {
            PayBillSummary summary = payBillParser.parse(stream, entry -> {
                // 逐行消费明细（如写库对账），永不堆积在内存
            });
            return Result.success(summary);
        }
    }
}
```

---

## 3. 生产扩展接管与高级定制 (Customization & Extensibility)

### 3.1 业务支付与退款异步回调监听器 SPI (`PayCallbackListener`)

实现 `PayCallbackListener` 并注册为 Spring Bean，框架在经过底层自动验签、解密与并发防重拦截后，自动分发强类型回调对象：

```java
@Slf4j
@Component
public class OrderPaymentCallbackListener implements PayCallbackListener {

    private final Set<String> processedOrders = ConcurrentHashMap.newKeySet();
    private final Set<String> processedRefunds = ConcurrentHashMap.newKeySet();

    @Override
    public boolean supports(PaymentSuccessCallback callback) {
        return callback != null && callback.getOutTradeNo() != null;
    }

    @Override
    public PayCallbackResult onPaymentSuccess(PaymentSuccessCallback callback) {
        String outTradeNo = callback.getOutTradeNo();

        // 1. 业务防重放检查：已履约订单直接返回 IGNORE 静默确认，防止重复发货
        if (processedOrders.contains(outTradeNo)) {
            log.info("Payment order [{}] already processed, returning IGNORE ACK.", outTradeNo);
            return PayCallbackResult.ignore("订单已处理，忽略重复通知");
        }

        // 2. 推进业务订单履约状态（更新订单为 PAID、核销卡券等）
        processedOrders.add(outTradeNo);
        log.info("Business order [{}] state updated to PAID.", outTradeNo);

        // 3. 返回 SUCCESS 指示网关向第三方渠道回复官方成功确认报文
        return PayCallbackResult.success("订单支付状态更新成功");
    }

    @Override
    public boolean supports(RefundSuccessCallback callback) {
        return callback != null && callback.getOutRefundNo() != null;
    }

    @Override
    public PayCallbackResult onRefundSuccess(RefundSuccessCallback callback) {
        String outRefundNo = callback.getOutRefundNo();

        if (processedRefunds.contains(outRefundNo)) {
            log.info("Refund [{}] already processed, returning IGNORE ACK.", outRefundNo);
            return PayCallbackResult.ignore("退款已处理，忽略重复通知");
        }

        processedRefunds.add(outRefundNo);
        log.info("Business refund [{}] state successfully updated.", outRefundNo);
        return PayCallbackResult.success("退款状态更新成功");
    }
}
```

---

### 3.2 动态多商户与多应用注册 (`NamedMerchantRegistry`)

通过 `NamedMerchantRegistry`，可在系统运行时动态注册新的微信商户号或支付宝应用，支持 SaaS 多租户按需切源：

```java
@Service
public class TenantPaymentConfigService {

    private final NamedMerchantRegistry merchantRegistry;

    public TenantPaymentConfigService(NamedMerchantRegistry merchantRegistry) {
        this.merchantRegistry = merchantRegistry;
    }

    public void registerNewTenant(String tenantId, String mchId, String appId, String privateKeyPath, String pubKeyPath, String pubKeyId) {
        WechatMerchantProperties props = new WechatMerchantProperties();
        props.setMchId(mchId);
        props.setAppId(appId);
        props.setPrivateKeyPath(privateKeyPath);
        props.setWechatPayPublicKeyPath(pubKeyPath);
        props.setWechatPayPublicKeyId(pubKeyId);

        merchantRegistry.registerWechatMerchant(tenantId, props);
    }
}
```

---

### 3.3 线程级动态商户切源与上下文隔离 (`PayContextHolder`)

在多租户网关或过滤器中，通过 `PayContextHolder.setMerchantKey("tenantA")` 动态绑定商户别名：

```java
try {
    PayContextHolder.setMerchantKey("tenant_enterprise_001");
    // 后续调用的 payTemplate.pay(...) 会自动选用 tenant_enterprise_001 对应密钥与配置
    payTemplate.pay(request);
} finally {
    PayContextHolder.clear(); // 严格清理现场，防止线程池污染
}
```

---

### 3.4 外部密钥与证书扩展 SPI (`PayCertificateProvider`)

当商户私钥保存在外部硬件密码机（HSM）、云厂商 KMS（阿里云 KMS / 腾讯云 KMS / AWS KMS）或 HashiCorp Vault 时，实现 `PayCertificateProvider` 接口接管证书与私钥的加载：

```java
@Component
public class CloudKmsCertificateProvider implements PayCertificateProvider {

    @Override
    public boolean supports(String locationOrContent) {
        return locationOrContent != null && locationOrContent.startsWith("kms://");
    }

    @Override
    public PrivateKey loadPrivateKey(String locationOrContent) {
        // 从企业云 KMS 解密并解析私钥
        return decryptPrivateKeyFromKms(locationOrContent);
    }

    @Override
    public X509Certificate loadCertificate(String locationOrContent) {
        return null;
    }
}
```

---

### 3.5 双轨并发防重排队锁自主接管 (`PayLockManager`)

默认情况下，框架自动探测运行环境：检测到 Redis 基础设施（`StringRedisTemplate`）时，自动装配 `RedisPayLockManager` 分布式锁；无 Redis 时自动降级为 `InMemoryPayLockManager`。开发者可通过声明自定义 Bean 覆盖默认实现：

```java
@Configuration(proxyBeanMethods = false)
public class CustomPayLockConfiguration {

    @Bean
    public PayLockManager customPayLockManager(RedissonClient redissonClient) {
        return new RedissonPayLockManager(redissonClient);
    }
}
```

---

### 3.6 官方权威原生 SDK 客户端逃生舱与单测桩注入

当企业需要调用冷门或专有扩展接口时，可通过逃生舱直接获取原生客户端或在测试时清理客户端持有者：
- `payTemplate.getWechatClient()`：获取腾讯官方 `com.wechat.pay.java.core.http.HttpClient`
- `payTemplate.getAlipayClient()`：获取蚂蚁金服官方 `com.alipay.api.AlipayClient`
- `payTemplate.getWechatPayTemplate()` / `payTemplate.getAlipayTemplate()`：获取专有独立门面
- `alipayTemplate.clearClientCache()`：清理应用客户端缓存（支持单测桩网络切换）
- `alipayTemplate.setBillStreamDownloader(...)`：注入自定义对账单流式下载器

---

## 4. 核心生产边界与非功能机制 (Production Boundary & Failure Modes)

### 4.1 微信支付平台证书自动轮换 vs 微信平台公钥直连双模引擎

遵循微信官方 V3 接口演进路线，`ateng-boot-pay` 提供双模平滑验证机制：
- **模式 A: 微信平台公钥直接验签引擎（官方主推，零网络交互）**：
  配置 `wechat-pay-public-key-path`（或 `wechat-pay-public-key-pem`）及 `wechat-pay-public-key-id` 后，框架通过 `PayKeyLoader` 自动加载公钥并在内存构建官方 `RSAPublicKeyConfig` 实例。出站请求与进站回调验签 100% 本地运算完成，免去周期性下载平台证书的网络开销与定时轮询隐患；
- **模式 B: 微信商户平台证书后台守护自动轮换引擎**：
  对于尚未迁移平台公钥的存量商户，配置 `auto-certificate: true` 后，`WechatAutoCertificateManager` 负责管理证书生命周期。新证书下载后，通过 `ConcurrentHashMap` 执行原子化热替换，旧证书保留至失效窗口期结束，彻底规避因证书未及时更新导致的验签雪崩。

---

### 4.2 线下条码付撤销冲正因果链与受控退避重试机制

条码收银面临网络超时、收银员提前取消或用户网络欠费等复杂异常：
- **微信支付冲正与重试治理 (ADR-0075 / #554)**：
  微信支付 V3 不提供撤销退款接口，必须调用官方撤销冲正接口 `/v3/pay/transactions/out-trade-no/{outTradeNo}/reverse`。
  当网络抖动或微信服务端返回 `SYSTEMERROR`（系统繁忙）时，`WechatPayTemplate.cancelOrder` 依托 Core 基础底座 `RetryUtil` 自动执行指数退避重试（最多 3 次，初始间隔 1 秒，倍率 2.0，上限 5 秒），确保订单冲正绝对确定性闭环，坚决杜绝超扣与悬挂；
- **支付宝当面付冲正治理**：
  支付宝调用官方 `alipay.trade.cancel` 接口。若交易已支付，支付宝自动执行原路冲正退款；若交易未支付，支付宝直接关闭订单。

---

### 4.3 双轨防重并发排队锁机制与防御性释放范式

- **原子加锁排队**：当第三方支付平台在极短时间内发起多次并发回调通知时，`PayLockManager` 以 `CHANNEL:outTradeNo` 或 `CHANNEL:outRefundNo` 为键执行原子加锁，默认租期为 10 秒；
- **锁争抢优雅重试**：遇到并发锁冲突（即上一笔通知正在处理中）时，网关立即向渠道回复失败（微信 HTTP 500 / 支付宝 `fail`），促使第三方支付平台进入阶梯式重试投递队列（微信按 15s/15s/30s/3m... 逐步重试），杜绝重复并发写操作；
- **防御性释放范式**：恪守九道成熟度防线第 5 条（严格无状态与并发纪律），锁释放必须且只能在 `finally` 块中执行，杜绝锁悬挂导致后续重试被永久阻塞。

---

### 4.4 “内存真明文”架构不变量与敏感凭证治理

- 严格恪守 [ADR-0012](../docs/adr/0012-lifecycle-separated-data-security.md) 与 [ADR-0048](../docs/adr/0048-enterprise-multi-channel-payment-architecture.md)：“内存真明文”架构不变量；
- 商户私钥、APIv3 密钥与支付凭证在内存中保持完整真实明文以支撑底层签名计算；
- 转账出参对象 `PayTransferResponse` 在 `toString()` 中自动调用 `DesensitizeUtil` 针对姓名 `payeeRealName` 与账号 `payeeAccount` 进行动态脱敏掩码，坚决杜绝明文凭证在日志中泄漏；
- **全域元契约与无损换算**：对外接口、DTO、VO 与领域模型中，凡涉及支付金额、退款金额统一强类型声明为 `BigDecimal`（保留两位小数，舍入模式 `RoundingMode.HALF_UP`），彻底消除浮点数 `Double` / `Float` 精度失真与无量纲整型歧义；在进入渠道边界前，统一调用 `CurrencyUtil.yuanToFen(amount)` 安全转换为分（`Long`）；出站回调与响应反向解析时，统一调用 `CurrencyUtil.fenToYuan(total)` 转回 `BigDecimal` 元。

---

### 4.5 财务对账单流式解压与零 OOM 内存防卫

- **多算法流式自适应**：`PayBillParser` 自动嗅探输入流 Magic Bytes，自适应识别 GZIP（微信官方）与 ZIP（支付宝官方）压缩流；
- **常驻 O(1) 内存防护**：针对海量交易对账文件，提供 `parse(stream, entryConsumer)` 流式回调 API，边解压边逐行解析回调，杜绝十万级账单全量加载至 JVM 堆内存导致 OOM。

---

## 5. 场景样例与全真验证 (ateng-boot-sample-pay)

完整、自洽、开箱即用的端到端演示代码 100% 收录于 `ateng-boot-samples/ateng-boot-sample-pay`，并通过真实 OkHttp `MockWebServer` 拦截出站请求驱动官方 SDK 真实握手：

### 5.1 场景控制器一览

- **统一收单场景控制器**：[`PayCheckoutController.java`](../ateng-boot-samples/ateng-boot-sample-pay/src/main/java/io/github/atengk/boot/sample/pay/controller/PayCheckoutController.java)（覆盖 Native 扫码、JSAPI 公众号/小程序、支付宝小程序收单、H5 移动网页与 App 调起）
- **统一退款场景控制器**：[`PayRefundController.java`](../ateng-boot-samples/ateng-boot-sample-pay/src/main/java/io/github/atengk/boot/sample/pay/controller/PayRefundController.java)（售后退款申请与状态查询）
- **单笔资金转账场景控制器**：[`PayTransferController.java`](../ateng-boot-samples/ateng-boot-sample-pay/src/main/java/io/github/atengk/boot/sample/pay/controller/PayTransferController.java)（单笔转账发起与到账查询）
- **微信资金分账场景控制器**：[`PayProfitSharingController.java`](../ateng-boot-samples/ateng-boot-sample-pay/src/main/java/io/github/atengk/boot/sample/pay/controller/PayProfitSharingController.java)（添加分账接收方与完结解冻在途资金）
- **线下条码收银场景控制器**：[`PayBarcodeController.java`](../ateng-boot-samples/ateng-boot-sample-pay/src/main/java/io/github/atengk/boot/sample/pay/controller/PayBarcodeController.java)（条码收银阶梯轮询扣款与超时自动撤销冲正）
- **财务对账单流式解析场景控制器**：[`PayBillController.java`](../ateng-boot-samples/ateng-boot-sample-pay/src/main/java/io/github/atengk/boot/sample/pay/controller/PayBillController.java)（全量内存解析与常驻 O(1) 流式边解压边消费）
- **业务支付/退款回调监听器**：[`OrderPaymentCallbackListener.java`](../ateng-boot-samples/ateng-boot-sample-pay/src/main/java/io/github/atengk/boot/sample/pay/listener/OrderPaymentCallbackListener.java)（防重放与业务状态流转）
- **端到端集成测试套件**：[`PaySampleIntegrationTest.java`](../ateng-boot-samples/ateng-boot-sample-pay/src/test/java/io/github/atengk/boot/sample/pay/PaySampleIntegrationTest.java)（22 个全真集成测试用例，100% 离线自洽通过）

---

### 5.2 22 项全场景集成测试用例矩阵

| 序号 | 测试方法名称 | 场景说明 | 核心验证断言 |
| :---: | :--- | :--- | :--- |
| 1 | `shouldReturnQrCodeForNativeCheckout` | 微信 Native 扫码收单 | 生成二维码内容链接，`tradeType=NATIVE` |
| 2 | `shouldReturnWechatJsapiParamsForJsapiCheckout` | 微信 JSAPI 小程序收单 | 服务端 RSA 二次加签，封包 5 元素调起凭据 |
| 3 | `shouldReturnPayUrlForH5Checkout` | 微信 H5 移动网页支付 | 生成专属跳转 URL，`tradeType=H5` |
| 4 | `shouldReturnPrepayIdForAppCheckout` | 微信 App 移动应用收单 | 返回预支付标识 `prepayId`，`tradeType=APP` |
| 5 | `shouldReturnSuccessForOrderQuery` | 微信交易订单状态查询 | 主动轮询交易流水，映射 `tradeState=SUCCESS` |
| 6 | `shouldReturnCloseOrderResult` | 微信未支付订单主动关单 | 成功向官方发起关单请求，映射 `tradeState=CLOSED` |
| 7 | `shouldReturnFormHtmlForAlipayPageCheckout` | 支付宝 PC 网站电脑收银台 | 返回自提交 HTML 表单脚本，`tradeType=PAGE` |
| 8 | `shouldReturnOrderStringForAlipayAppCheckout` | 支付宝 App 客户端调起收单 | 生成客户端 SDK 加签调起串，`tradeType=APP` |
| 9 | `shouldHandleWechatPaymentCallbackSuccessfully` | 微信支付异步 Webhook 回调 | 自动拦截、验签、解密通知报文，分发业务监听器 |
| 10 | `shouldHandleAlipayPaymentCallbackSuccessfully` | 支付宝异步 Webhook 回调 | 自动拦截、验签表单入参，分发业务监听器 |
| 11 | `shouldReturnFailedResultWhenSignatureInvalid` | 非法签名防重放拦截 | 非法篡改签名直接快速失败拦截，保障资金安全 |
| 12 | `shouldApplyRefundSuccessfully` | 微信统一售后退款发起 | 输出统一退款凭据，`status=PROCESSING` |
| 13 | `shouldQueryRefundSuccessfully` | 微信统一退款状态查询 | 查询退款流水进度，映射 `status=SUCCESS` |
| 14 | `shouldProcessRefundCallbackSuccessfully` | 微信退款成功异步通知与防重 | 自动解密退款通知，业务多次通知触发 IGNORE 幂等 |
| 15 | `shouldApplySingleTransferSuccessfully` | 支付宝单笔资金转账发起 | 输出统一转账凭据，`status=SUCCESS` |
| 16 | `shouldQuerySingleTransferSuccessfully` | 支付宝单笔资金转账查询 | 主动查询转账单据状态，返回订单流水与状态 |
| 17 | `shouldCashierBarcodePaySuccessfully` | 线下条码收银面对面扣款 | 模拟扫码扣款，即时返回 `tradeState=SUCCESS` |
| 18 | `shouldDownloadAndParseBillInMemory` | 财务对账单流式全量内存解析 | 解压 GZIP 账单并解析明细条目与汇总统计 |
| 19 | `shouldStreamReconcileBillZeroOom` | 财务对账单常驻 O(1) 零 OOM 流式对账 | 边流式解压边逐行回调消费，无内存堆积风险 |
| 20 | `shouldAddProfitSharingReceiverAndUnfreeze` | 微信资金分账接收方与解冻 | 成功添加分账接收方，完结解冻在途剩余资金 |
| 21 | `shouldReturnTradeNoForAlipayMiniAppCheckout` | 支付宝小程序统一收单创建 | 调用真实 `alipay.trade.create`，返回 `tradeNo` |
| 22 | `shouldCancelAndReverseBarcodeCashierOnTimeout`| 条码收银等待超时自动撤销冲正 | 全真执行冲正 `/reverse` 接口，返回 `tradeState=REVOKED` |

---

### 5.3 真实测试通过执行日志截取

```text
[INFO] Reactor Summary for io.github.atengk:ateng-boot 1.2.0:
[INFO] 
[INFO] io.github.atengk:ateng-boot ........................ SUCCESS [  0.196 s]
[INFO] io.github.atengk:ateng-boot-core ................... SUCCESS [  5.113 s]
[INFO] io.github.atengk:ateng-boot-web .................... SUCCESS [  7.410 s]
[INFO] io.github.atengk:ateng-boot-pay .................... SUCCESS [ 14.536 s]
[INFO] io.github.atengk:ateng-boot-samples ................ SUCCESS [  0.002 s]
[INFO] io.github.atengk:ateng-boot-sample-pay ............. SUCCESS [ 30.291 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  52.479 s (Wall Clock)
[INFO] Finished at: 2026-10-31T21:41:21+08:00
[INFO] ------------------------------------------------------------------------
```

### 验证全绿命令

```bash
# 精准定向验收 ateng-boot-pay 核心底座与 sample-pay 样例场景
mvn test -pl ateng-boot-pay,ateng-boot-samples/ateng-boot-sample-pay -am -T 1C -nsu -B
```
