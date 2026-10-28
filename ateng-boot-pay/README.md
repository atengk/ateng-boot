# ateng-boot-pay 基础设施 Starter

> 工业级企业多渠道专有支付中间件底座 Starter，深度集成腾讯官方权威 `wechatpay-java:0.2.17` 与蚂蚁金服 `alipay-sdk-java:4.40.1044.ALL` 原生 SDK，支持双轨专有独立门面 `WechatPayTemplate` 与 `AlipayTemplate`、顶层轻量统一收单门面 `PayTemplate`、资金售后退款、单笔转账到零钱/账户、订单资金分账解冻、线下收银台条码付自动轮询冲正、财务对账单流式解压解析引擎、四模密钥自适应解析加载、微信平台证书自动轮换与平滑衰退引擎、双轨异步 Webhook 自动化验签解密、双模防重并发锁、强类型业务回调监听器 SPI 与全域 BigDecimal 元契约治理。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.pay.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **统一收单** | 统一多渠道收单路由 | `PayTemplate` | `ateng.boot.pay.enabled` | `pay(UnifiedPayRequest)`，多渠道自动路由，输出扁平凭据 `PayResponse`（`qrCode` / `payUrl` / `formHtml` / `wechatJsapi` / `orderString`） | `PayTemplateTest` / `PaySampleIntegrationTest` |
| **统一收单** | 统一交易状态查询 | `PayTemplate` | `ateng.boot.pay.enabled` | `queryOrder(UnifiedQueryRequest)` / `queryOrder(PayChannel, String)` / `queryOrder(PayChannel, String, String)`，主动查询订单生命周期状态 | `PayTemplateTest` / `PaySampleIntegrationTest` |
| **统一收单** | 统一未付款订单关闭 | `PayTemplate` | `ateng.boot.pay.enabled` | `closeOrder(UnifiedCloseRequest)` / `closeOrder(PayChannel, String)` / `closeOrder(PayChannel, String, String)`，主动关闭未付款超时订单 | `PayTemplateTest` / `PaySampleIntegrationTest` |
| **统一收单** | 统一交易订单撤销/冲正 | `PayTemplate` | `ateng.boot.pay.enabled` | `cancelOrder(UnifiedCancelRequest)` / `cancelOrder(PayChannel, String)` / `cancelOrder(PayChannel, String, String)`，超时未决撤销与关单冲正 | `PayTemplateTest` |
| **统一退款** | 统一售后退款发起 | `PayTemplate` | `ateng.boot.pay.enabled` | `refund(UnifiedRefundRequest)`，支持原路全额与部分多次退款，输出统一退款凭据 `PayRefundResponse` | `PayTemplateTest` / `PaySampleIntegrationTest` |
| **统一退款** | 统一退款状态查询 | `PayTemplate` | `ateng.boot.pay.enabled` | `queryRefund(UnifiedRefundQueryRequest)` / `queryRefund(PayChannel, String)` / `queryRefund(PayChannel, String, String)`，查询退款进度与入账流水 | `PayTemplateTest` / `PaySampleIntegrationTest` |
| **统一转账** | 统一单笔资金转账发起 | `PayTemplate` | `ateng.boot.pay.transfer.enabled` | `transfer(UnifiedTransferRequest)`，商家转账到微信零钱与支付宝单笔转账，输出 `PayTransferResponse` | `PayTemplateTest` / `PaySampleIntegrationTest` |
| **统一转账** | 统一单笔转账状态查询 | `PayTemplate` | `ateng.boot.pay.transfer.enabled` | `queryTransfer(UnifiedTransferQueryRequest)` / `queryTransfer(PayChannel, String)` / `queryTransfer(PayChannel, String, String)`，查询转账到账状态 | `PayTemplateTest` / `PaySampleIntegrationTest` |
| **对账单流** | 财务对账单流式下载 | `PayTemplate` | `ateng.boot.pay.enabled` | `downloadBillStream(PayBillDownloadRequest)`，流式下载渠道官方明细对账单输入流 | `PayTemplateTest` / `PaySampleIntegrationTest` |
| **统一逃生舱** | 便捷原生客户端逃生舱 | `PayTemplate` | - | `getWechatClient()` 获取微信 `HttpClient`；`getAlipayClient()` 获取支付宝 `AlipayClient`；`getWechatTemplate()` 与 `getAlipayTemplate()` 获取独立专有门面 | `PayTemplateTest` |
| **条码收银** | 线下付款码收银轮询执行器 | `BarcodePayExecutor` | `ateng.boot.pay.barcode.max-wait-time`<br>`ateng.boot.pay.barcode.poll-interval`<br>`ateng.boot.pay.barcode.auto-cancel-on-timeout` | `execute(UnifiedPayRequest)` 与 `execute(UnifiedPayRequest, Duration, Duration)`，阶梯式轮询支付结果，超时自动撤销冲正 | `BarcodePayExecutorTest` / `PaySampleIntegrationTest` |
| **账单解析** | 财务对账单流式解压解析引擎 | `PayBillParser` | - | `parse(InputStream)` 全量内存解析为 `PayBillResult`；`parse(InputStream, Consumer<PayBillEntry>)` 零 OOM 流式逐行解压与回调 | `PayBillParserTest` / `PaySampleIntegrationTest` |
| **微信专有** | Native 扫码收单 | `WechatPayTemplate` | `ateng.boot.pay.wechat.enabled`<br>`ateng.boot.pay.wechat.merchants.<key>.*` | `payNative(WechatPayRequest)`，直连商户与特约服务商扫码预下单，返回 `qrCode` 二维码内容链接 | `WechatPayTemplateTest` / `PaySampleIntegrationTest` |
| **微信专有** | JSAPI 小程序/公众号支付 | `WechatPayTemplate` | `ateng.boot.pay.wechat.merchants.<key>.app-id`<br>`ateng.boot.pay.wechat.merchants.<key>.private-key-pem` | `payJsapi(WechatPayRequest)`，服务端全自动 RSA 二次加签，封包 5 元素直达凭据 `wechatJsapi` | `WechatPayTemplateTest` / `PaySampleIntegrationTest` |
| **微信专有** | H5 移动网页支付 | `WechatPayTemplate` | `ateng.boot.pay.wechat.merchants.<key>.*` | `payH5(WechatPayRequest)`，生成手机浏览器专属跳转支付链接 `payUrl`（`h5_url`） | `WechatPayTemplateTest` / `PaySampleIntegrationTest` |
| **微信专有** | App 移动客户端支付 | `WechatPayTemplate` | `ateng.boot.pay.wechat.merchants.<key>.*` | `payApp(WechatPayRequest)`，服务端计算开放平台 App SDK 调起签名，封包 `wechatApp` 凭据 | `WechatPayTemplateTest` / `PaySampleIntegrationTest` |
| **微信专有** | 条码付 / 付款码被扫 | `WechatPayTemplate` | `ateng.boot.pay.wechat.merchants.<key>.*` | `payBarcode(WechatPayRequest)`，线下商户扫描用户微信付款码收单（`auth_code`） | `WechatPayTemplateTest` |
| **微信专有** | 微信订单状态查询 | `WechatPayTemplate` | `ateng.boot.pay.wechat.merchants.<key>.*` | `queryOrder(WechatPayRequest)` / `queryOrder(String)` / `queryOrder(String, String)`，微信订单状态机自动映射 | `WechatPayTemplateTest` |
| **微信专有** | 微信未支付订单关闭 | `WechatPayTemplate` | `ateng.boot.pay.wechat.merchants.<key>.*` | `closeOrder(WechatPayRequest)` / `closeOrder(String)` / `closeOrder(String, String)`，关闭超时未支付会话 | `WechatPayTemplateTest` |
| **微信专有** | 微信售后退款发起与查询 | `WechatPayTemplate` | `ateng.boot.pay.wechat.merchants.<key>.*` | `refund(WechatRefundRequest)` 发起退款；`queryRefund(WechatRefundQueryRequest)` / `queryRefund(String)` 查询微信退款状态 | `WechatPayTemplateTest` |
| **微信专有** | 微信官方对账单下载 | `WechatPayTemplate` | `ateng.boot.pay.wechat.merchants.<key>.*` | `downloadTradeBill(...)` 下载交易账单；`downloadFundFlowBill(...)` 下载资金账单，自动校验 SHA1 摘要并返回流 | `WechatPayTemplateTest` |
| **微信分账** | 微信订单资金分账服务 | `WechatProfitSharingService` | `ateng.boot.pay.profit-sharing.enabled` | 封装 `createOrder` 请求分账、`queryOrder` 查询分账、`unfreezeOrder` 完结解冻在途资金、`addReceiver`/`deleteReceiver` 接收方管理、`createReturnOrder` 分账回退 | `WechatProfitSharingServiceTest` / `PaySampleIntegrationTest` |
| **微信转账** | 商家转账到微信零钱服务 | `WechatTransferService` | `ateng.boot.pay.transfer.enabled` | `transferToPocket(String, WechatTransferRequest)` 单笔转账到零钱（自动加载微信平台公钥加密收款人真实姓名）；`queryTransferDetail` 查询转账明细 | `WechatTransferServiceTest` |
| **微信逃生舱** | 原生客户端与配置获取 | `WechatPayTemplate` | - | `getClient(String)` / `getClient()` / `getHttpClient()` 获取官方 `HttpClient`；`getWechatConfig(String)` 获取官方 `Config` | `WechatPayTemplateTest` |
| **支付宝专有** | 电脑网站支付 (Page) | `AlipayTemplate` | `ateng.boot.pay.alipay.enabled`<br>`ateng.boot.pay.alipay.apps.<key>.*` | `payPage(AlipayPayRequest)`，PC 端收银台，返回 `formHtml` 包含自提交 HTML 表单与脚本 | `AlipayTemplateTest` |
| **支付宝专有** | 手机网站支付 (Wap) | `AlipayTemplate` | `ateng.boot.pay.alipay.apps.<key>.*` | `payWap(AlipayPayRequest)`，手机浏览器收银台，返回 `payUrl` 跳转链接或 `formHtml` | `AlipayTemplateTest` |
| **支付宝专有** | App 移动客户端支付 | `AlipayTemplate` | `ateng.boot.pay.alipay.apps.<key>.*` | `payApp(AlipayPayRequest)`，生成支付宝官方客户端 SDK 加签调起串 `orderString` | `AlipayTemplateTest` / `PaySampleIntegrationTest` |
| **支付宝专有** | 当面付扫码预下单 (Qr) | `AlipayTemplate` | `ateng.boot.pay.alipay.apps.<key>.*` | `payQr(AlipayPayRequest)`，线下收单生成支付宝付款二维码内容链接 `qrCode` | `AlipayTemplateTest` |
| **支付宝专有** | 当面付条码收单 (Barcode) | `AlipayTemplate` | `ateng.boot.pay.alipay.apps.<key>.*` | `payBarcode(AlipayPayRequest)`，商户扫描用户支付宝付款码收单（`auth_code`） | `AlipayTemplateTest` |
| **支付宝专有** | 支付宝订单状态查询 | `AlipayTemplate` | `ateng.boot.pay.alipay.apps.<key>.*` | `queryOrder(AlipayPayRequest)` / `queryOrder(String)` / `queryOrder(String, String)`，查询支付宝收单流水 | `AlipayTemplateTest` |
| **支付宝专有** | 支付宝未支付订单关闭 | `AlipayTemplate` | `ateng.boot.pay.alipay.apps.<key>.*` | `closeOrder(AlipayPayRequest)` / `closeOrder(String)` / `closeOrder(String, String)`，关闭超时订单 | `AlipayTemplateTest` |
| **支付宝专有** | 支付宝已支付订单撤销 | `AlipayTemplate` | `ateng.boot.pay.alipay.apps.<key>.*` | `cancelOrder(AlipayPayRequest)` / `cancelOrder(String)` / `cancelOrder(String, String)`，当面付交易撤销与冲正 | `AlipayTemplateTest` |
| **支付宝专有** | 支付宝售后退款与查询 | `AlipayTemplate` | `ateng.boot.pay.alipay.apps.<key>.*` | `refund(AlipayRefundRequest)` 支持部分退款与分账同步退款；`queryRefund(AlipayRefundQueryRequest)` 查询退款明细 | `AlipayTemplateTest` |
| **支付宝专有** | 支付宝账单下载链接查询 | `AlipayTemplate` | `ateng.boot.pay.alipay.apps.<key>.*` | `downloadBillUrl(String, String, String)`，获取官方账单 30 秒有效临时下载 URL | `AlipayTemplateTest` |
| **支付宝分账** | 支付宝订单分账结算服务 | `AlipayProfitSharingService` | `ateng.boot.pay.profit-sharing.enabled` | 封装 `relationBind`/`relationUnbind` 分账关系绑定解绑、`settle` 订单分账结算、`settleQuery` 分账查询与 `rateQuery` 比例查询 | `AlipayProfitSharingServiceTest` |
| **支付宝转账** | 支付宝单笔转账到账户服务 | `AlipayTransferService` | `ateng.boot.pay.transfer.enabled` | `transfer(String, AlipayTransferRequest)` 单笔转账到支付宝账户/银行卡；`queryTransfer` 查询转账单据状态 | `AlipayTransferServiceTest` |
| **支付宝逃生舱** | 原生客户端获取 | `AlipayTemplate` | - | `getClient(String)` / `getClient()` / `getAlipayClient()` 获取蚂蚁金服官方原生 `AlipayClient` | `AlipayTemplateTest` |
| **回调网关** | 统一 Webhook 端点 | `PayWebhookController` | `ateng.boot.pay.callback.enabled`<br>`ateng.boot.pay.callback.path-prefix` | 暴露统一端点 `/ateng-pay/callback/{channel}/{merchantId}`，自动拦截、验签、解密与并发防重 | `PayWebhookControllerTest` / `PaySampleIntegrationTest` |
| **回调处理** | 编程式回调处理门面 | `PayCallbackHandler` | `ateng.boot.pay.callback.idempotent-timeout` | `handleWechat(...)` 与 `handleAlipay(...)`，支持 Servlet 请求或解耦参数对象编程式调度 | `PayWebhookControllerTest` / `PaySampleIntegrationTest` |
| **回调监听 SPI** | 支付与退款异步监听器 | `PayCallbackListener` | - | 实现 `onPaymentSuccess(PaymentSuccessCallback)` 与 `onRefundSuccess(RefundSuccessCallback)`，返回确定性 `PayCallbackResult` | `OrderPaymentCallbackListener` / `PaySampleIntegrationTest` |
| **证书扩展 SPI** | 外部密钥证书提供器 | `PayCertificateProvider` | - | 实现外部 KMS / HashiCorp Vault / 数据库动态解密私钥与证书扩展接口 | `PayKeyLoaderTest` |
| **多商户注册** | 命名多商户多应用管理 | `NamedMerchantRegistry` | `ateng.boot.pay.wechat.merchants.*`<br>`ateng.boot.pay.alipay.apps.*` | 运行时动态注册与热更新商户/应用配置，支持三级降级查找（显式键 -> 默认键 -> 首个已注册） | `NamedMerchantRegistryTest` |
| **线程切源** | 线程级动态切源持有者 | `PayContextHolder` | - | `setMerchantKey(String)` / `getMerchantKey()` / `clear()`，支持多租户与请求上下文动态绑定商户别名 | `PayContextHolderTest` |
| **密钥解析** | 四模自适应密钥加载器 | `PayKeyLoader` | - | 自动识别 `classpath:`、`file:`、纯文本 PEM（`loadPrivateKeyPem` / `loadPublicKeyPem`）与纯 Base64 编码 | `PayKeyLoaderTest` |
| **证书轮换** | 微信平台证书自动轮换 | `WechatAutoCertificateManager` | `ateng.boot.pay.wechat.merchants.<key>.auto-certificate` | 微信商户平台证书后台守护自动轮换、内存平滑切换与历史证书防雪崩衰退管理 | `WechatAutoCertificateManagerTest` |
| **防重并发锁** | 双轨分布式防重锁门面 | `PayLockManager` | `ateng.boot.pay.callback.idempotent-timeout` | `tryLock(String, Duration)` 与 `unlock(String)`，Redis 基础设施存在时优先装配分布式锁，无 Redis 内存锁保底 | `InMemoryPayLockManagerTest` / `RedisPayLockManagerTest` |
| **货币工具** | 元/分无损高精度换算 | `CurrencyUtil` | - | `yuanToFen(BigDecimal)` 与 `fenToYuan(Long)` / `fenToYuan(Integer)`，防御浮点精度失真 | `CurrencyUtilTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-pay` 是面向企业电商收单、SaaS 订阅缴费、政企在线支付与资金清结算的生产就绪专有支付基础设施：
- **纯净依赖与双规权威 SDK**：核心网络交互与协议解密 100% 对齐腾讯官方权威 `wechatpay-java:0.2.17` 与蚂蚁金服 `alipay-sdk-java:4.40.1044.ALL`，杜绝任何第三方未经安全审计的二次封装工具包；
- **全场景统一收单门面与扁平直达凭据**：统一轻量收单门面 `PayTemplate` 屏蔽微信与支付宝异构协议差异，出参采用扁平化统一凭据 `PayResponse`（前端按需直接取用 `qrCode`、`payUrl`、`formHtml`、`wechatJsapi`、`orderString`），彻底消除传统多态强转与 `ClassCastException` 隐患；
- **传输层与持久层绝对隔离 (Zero-Entity-Leakage)**：Controller 传输边界 100% 严禁暴露 ORM 实体，统一使用入参 DTO 挂载 JSR-303 格式校验与出参强类型 `Result<T>`（严格恪守 [ADR-0002](../docs/adr/0002-explicit-result-contract-no-response-body-advice.md) 与 [ADR-0010](../docs/adr/0010-transport-pagination-contract-decoupling.md)）；
- **全域强类型与全自动二次加签**：针对微信小程序/公众号 JSAPI 统一下单，全自动在服务端使用商户私钥计算 RSA 数字签名并封包 5 元素调起凭据（`appId`、`timeStamp`、`nonceStr`、`package`、`signType`、`paySign`），前端一行代码即可直接调用 `wx.requestPayment`；
- **双轨异步 Webhook 网关与并发防重锁**：统一映射端点 `/ateng-pay/callback/{channel}/{merchantId}`，自动拦截、验签、解密入站通知，结合 `PayLockManager`（Redis 优先 + 本地内存保底）实现 10 秒并发防重排队，业务只关注实现 `PayCallbackListener` SPI；
- **全域资金售后与分账转账治理**：完备覆盖原路退款、单笔转账到零钱/账户、分账接收方管理与剩余资金完结解冻、线下收银台轮询防超扣冲正，以及财务对账单流式解压解析。

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

```yaml
server:
  port: 8080

ateng:
  boot:
    pay:
      enabled: true
      # 异步 Webhook 回调网关与并发防重排队配置
      callback:
        enabled: true
        path-prefix: /ateng-pay/callback
        idempotent-timeout: 10s
      # 单笔资金转账特性开关
      transfer:
        enabled: true
      # 订单资金分账特性开关
      profit-sharing:
        enabled: true
      # 线下收银台条码支付轮询与冲正配置
      barcode:
        max-wait-time: 30s
        poll-interval: 3s
        auto-cancel-on-timeout: true
      # 微信支付官方 V3 配置
      wechat:
        enabled: true
        default-merchant: default
        merchants:
          default:
            app-id: wx8888888888888888
            mch-id: "1900000001"
            api-v3-key: "32bytesMockApiV3KeyForTesting000"
            merchant-serial-number: "1234567890ABCDEF1234567890ABCDEF"
            private-key-pem: |
              -----BEGIN PRIVATE KEY-----
              MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC7V3D90WOm1q89...
              -----END PRIVATE KEY-----
            notify-url: "http://127.0.0.1:8080/ateng-pay/callback/wechat"
            auto-certificate: true
      # 支付宝官方开放平台配置
      alipay:
        enabled: true
        default-app: default
        apps:
          default:
            app-id: "2021000000000001"
            private-key: "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC7..."
            alipay-public-key: "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."
            notify-url: "http://127.0.0.1:8080/ateng-pay/callback/alipay"
            return-url: "http://127.0.0.1:8080/pay/return"
            server-url: "https://openapi-sandbox.dl.alipaydev.com/gateway.do"
```

### 2.2 统一收单体验（以 Native 扫码与 JSAPI 为例）

```java
@RestController
@RequestMapping("/pay/checkout")
public class PayCheckoutController {

    private final PayTemplate payTemplate;

    public PayCheckoutController(PayTemplate payTemplate) {
        this.payTemplate = Objects.requireNonNull(payTemplate, "PayTemplate 不能为空");
    }

    /**
     * 1. Native 扫码预下单，返回 qrCode 供前端渲染二维码
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
     * 2. JSAPI 小程序/公众号下单，全自动服务端 RSA 二次加签
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
                .build();

        PayResponse response = payTemplate.pay(request);
        return Result.success(response);
    }
}
```

响应 JSON 扁平直达凭据示例：

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "channel": "WECHAT_PAY",
    "tradeType": "NATIVE",
    "outTradeNo": "ORD_NATIVE_20261023_001",
    "amount": 99.90,
    "success": true,
    "qrCode": "weixin://wxpay/bizpayurl?pr=mockQrCode999"
  },
  "success": true,
  "traceId": "ad948f98a2fb455c832283e1074e4fe8"
}
```

### 2.3 售后退款申请与状态查询

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

### 2.4 单笔资金转账申请与查询

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

### 2.5 微信资金分账接收方添加与资金完结解冻

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
        request.setName(dto.getName());
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

### 2.6 线下条码收银台同步轮询扣款

```java
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

        // 内部阶梯式轮询支付结果（默认30s超时/3s轮询间隔），遇到超时或异常自动触发关单撤销冲正
        PayResponse response = barcodePayExecutor.execute(request);
        return Result.success(response);
    }
}
```

### 2.7 财务对账单流式下载与解析（全量内存与零 OOM 流式消费）

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
     * 模式一：全量内存模式，下载并解析出包含所有明细的 PayBillResult
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

        AtomicLong counter = new AtomicLong();
        try (InputStream stream = payTemplate.downloadBillStream(request)) {
            PayBillSummary summary = payBillParser.parse(stream, entry -> {
                counter.incrementAndGet();
                // 逐行消费明细，例如落库或与本地流水对账
            });
            return Result.success(summary);
        }
    }
}
```

---

## 3. 生产扩展与自主接管 (Customization & Extensibility)

### 3.1 业务支付与退款异步回调监听器 SPI (`PayCallbackListener`)

实现 `PayCallbackListener` 接口并注册为 Spring Bean，框架在经过底层自动验签、解密与并发防重拦截后，自动分发强类型事件 `PaymentSuccessCallback` 或 `RefundSuccessCallback`：

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

        // 1. 业务防重放检查：已处理过的订单直接返回 IGNORE 静默确认，防止重复处理
        if (processedOrders.contains(outTradeNo)) {
            log.info("Payment order [{}] has already been processed, returning IGNORE ACK.", outTradeNo);
            return PayCallbackResult.ignore("订单已处理，忽略重复通知");
        }

        // 2. 推进业务订单履约状态（如更新订单为 PAID、核销卡券、生成发货单等）
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
            log.info("Refund [{}] has already been processed previously, returning IGNORE ACK.", outRefundNo);
            return PayCallbackResult.ignore("退款已处理，忽略重复通知");
        }

        processedRefunds.add(outRefundNo);
        log.info("Business refund [{}] state successfully updated in database.", outRefundNo);
        return PayCallbackResult.success("退款状态更新成功");
    }
}
```

### 3.2 动态多商户与多应用注册 (`NamedMerchantRegistry`)

通过 `NamedMerchantRegistry`，可在系统运行时动态注册新的微信商户号或支付宝应用，支持 SaaS 多租户按需扩展而无需重启服务：

```java
@Service
public class TenantPaymentConfigService {

    private final NamedMerchantRegistry merchantRegistry;

    public TenantPaymentConfigService(NamedMerchantRegistry merchantRegistry) {
        this.merchantRegistry = merchantRegistry;
    }

    public void registerNewTenantMerchant(String tenantId, String mchId, String appId, String privateKeyPem) {
        WechatMerchantProperties props = new WechatMerchantProperties();
        props.setMchId(mchId);
        props.setAppId(appId);
        props.setPrivateKeyPem(privateKeyPem);
        props.setAutoCertificate(true);

        merchantRegistry.registerWechatMerchant(tenantId, props);
    }
}
```

### 3.3 线程级动态商户切源 (`PayContextHolder`)

在多租户网关或过滤器中，通过 `PayContextHolder.setMerchantKey("tenantA")` 动态绑定商户别名，请求链路中的 `payTemplate`、`wechatPayTemplate` 会自动选用对应的配置，执行完毕后严格在 `finally` 中调用 `PayContextHolder.clear()`。

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
        return decryptFromKms(locationOrContent);
    }

    @Override
    public X509Certificate loadCertificate(String locationOrContent) {
        return null;
    }
}
```

### 3.5 双轨并发防重锁自主接管 (`PayLockManager`)

默认情况下，框架自动探测运行环境：当检测到 Redis 基础设施（`StringRedisTemplate`）时，自动装配 `RedisPayLockManager` 分布式锁；无 Redis 时自动降级为 `InMemoryPayLockManager`。开发者亦可通过声明自定义 Bean 覆盖默认锁实现：

```java
@Configuration(proxyBeanMethods = false)
public class CustomPayLockConfiguration {

    @Bean
    public PayLockManager customPayLockManager(RedissonClient redissonClient) {
        return new RedissonPayLockManager(redissonClient);
    }
}
```

### 3.6 官方权威原生 SDK 客户端逃生舱

当企业有冷门或者尚未标准化的官方扩展接口调用需求时，可随时通过逃生舱直接获取原生客户端：
- `payTemplate.getWechatClient()`：获取腾讯官方 `com.wechat.pay.java.core.http.HttpClient`
- `payTemplate.getAlipayClient()`：获取蚂蚁金服官方 `com.alipay.api.AlipayClient`
- `payTemplate.getWechatTemplate()` / `payTemplate.getAlipayTemplate()`：获取专有独立门面

---

## 4. 核心生产边界与非功能机制 (Production Boundary & Failure Modes)

### 4.1 微信平台证书自动轮换与平滑衰退机制

- **自动轮换周期**：微信支付平台证书有效期为 5 年，微信平台会提前自动签发新证书。`WechatAutoCertificateManager` 负责管理证书生命周期；
- **内存原子替换**：下载新证书后，通过 `ConcurrentHashMap` 执行原子化热替换，旧证书平滑保留至失效窗口期结束，彻底规避因证书未及时更新导致的线上验签雪崩；
- **新版公钥模式自适应**：商户若配置了 `wechat-pay-public-key`，框架优先使用微信官方新版静态公钥直接验签，兼具极致性能与免证书下载优势。

### 4.2 双轨防重并发锁机制与防御性锁释放

- **原子加锁排队**：当第三方支付平台在极短时间内发起多次并发回调通知时，`PayLockManager` 以 `CHANNEL:outTradeNo` 或 `CHANNEL:outRefundNo` 为键执行原子加锁，默认租期为 10 秒；
- **锁争抢优雅重试**：遇到并发锁冲突（即上一笔通知正在处理中）时，网关立即向渠道回复失败（微信 HTTP 500 / 支付宝 `fail`），促使第三方支付平台进入阶梯式重试投递队列（微信按 15s/15s/30s/3m... 逐步重试），杜绝重复并发写操作；
- **防御性释放范式**：恪守九道成熟度防线第 5 条（严格无状态与并发纪律），锁释放必须且只能在 `finally` 块中执行，杜绝锁悬挂导致后续重试被永久阻塞。

### 4.3 “内存真明文”与敏感凭据治理不变量

- 严格恪守 [ADR-0012](../docs/adr/0012-lifecycle-separated-data-security.md) 与 [ADR-0048](../docs/adr/0048-enterprise-multi-channel-payment-architecture.md)：“内存真明文”架构不变量；
- 商户私钥、APIv3 密钥与支付凭证在内存中保持完整真实明文以支撑底层签名计算；
- 转账出参对象 `PayTransferResponse` 在 `toString()` 中自动调用 `DesensitizeUtil` 针对姓名 `payeeRealName` 与账号 `payeeAccount` 进行动态脱敏掩码，坚决杜绝明文凭证在日志中泄漏。

### 4.4 全域 BigDecimal 元契约与货币无损换算

- **全域元契约**：对外接口、DTO、VO 与领域模型中，凡涉及支付金额、退款金额统一强类型声明为 `BigDecimal`（保留两位小数，舍入模式 `RoundingMode.HALF_UP`），彻底消除浮点数 `Double` / `Float` 精度失真与无量纲整型歧义；
- **底层精准换算**：在进入微信支付官方 SDK 边界前，统一调用 `CurrencyUtil.yuanToFen(amount)` 安全转换为分（`Long`）；出站回调与响应反向解析时，统一调用 `CurrencyUtil.fenToYuan(total)` 转回 `BigDecimal` 元，确保上下游契约纯净一致。

### 4.5 线下条码收银状态机与超时自动撤销冲正

- **状态机闭环**：线下收银扫描用户付款码后，若返回 `USERPAYING`（等待用户输入密码），`BarcodePayExecutor` 自动进入阶梯式同步轮询状态机；
- **防超扣冲正防护**：在轮询超过设定阈值（默认 30s）仍未完成扣款时，执行器自动向支付渠道发起订单撤销（`cancelOrder`）或关单冲正，确保资金不发生单边入账或超扣。

### 4.6 财务对账单内存安全与零 OOM 流式解压防护

- **多算法流式自适应**：`PayBillParser` 自动嗅探输入流 Magic Bytes，自适应识别 GZIP（微信官方）与 ZIP（支付宝官方）压缩流；
- **常驻 O(1) 内存防护**：针对海量交易对账文件，提供 `parse(stream, entryConsumer)` 流式回调 API，边解压边逐行解析回调，杜绝十万级账单全量加载至 JVM 堆内存导致 OOM。

---

## 5. 场景样例与全真验证 (ateng-boot-sample-pay)

完整、自洽、开箱即用的端到端演示代码 100% 收录于 `ateng-boot-samples/ateng-boot-sample-pay`：
- **统一收单场景控制器**：[PayCheckoutController.java](../ateng-boot-samples/ateng-boot-sample-pay/src/main/java/io/github/atengk/boot/sample/pay/controller/PayCheckoutController.java)
- **统一退款场景控制器**：[PayRefundController.java](../ateng-boot-samples/ateng-boot-sample-pay/src/main/java/io/github/atengk/boot/sample/pay/controller/PayRefundController.java)
- **单笔资金转账场景控制器**：[PayTransferController.java](../ateng-boot-samples/ateng-boot-sample-pay/src/main/java/io/github/atengk/boot/sample/pay/controller/PayTransferController.java)
- **微信资金分账场景控制器**：[PayProfitSharingController.java](../ateng-boot-samples/ateng-boot-sample-pay/src/main/java/io/github/atengk/boot/sample/pay/controller/PayProfitSharingController.java)
- **线下条码收银场景控制器**：[PayBarcodeController.java](../ateng-boot-samples/ateng-boot-sample-pay/src/main/java/io/github/atengk/boot/sample/pay/controller/PayBarcodeController.java)
- **财务对账单流式解析场景控制器**：[PayBillController.java](../ateng-boot-samples/ateng-boot-sample-pay/src/main/java/io/github/atengk/boot/sample/pay/controller/PayBillController.java)
- **业务支付/退款回调监听器**：[OrderPaymentCallbackListener.java](../ateng-boot-samples/ateng-boot-sample-pay/src/main/java/io/github/atengk/boot/sample/pay/listener/OrderPaymentCallbackListener.java)
- **100% 离线自洽集成测试 (20/20 全绿)**：[PaySampleIntegrationTest.java](../ateng-boot-samples/ateng-boot-sample-pay/src/test/java/io/github/atengk/boot/sample/pay/PaySampleIntegrationTest.java)

### 验证全绿命令

```bash
# 精准定向验收 ateng-boot-pay 核心底座与 sample-pay 样例场景
mvn test -pl ateng-boot-pay,ateng-boot-samples/ateng-boot-sample-pay -am -T 1C -nsu -B
```
