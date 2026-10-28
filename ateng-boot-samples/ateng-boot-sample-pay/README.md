# ateng-boot-sample-pay 企业级多渠道支付场景自验工程

> 专一 `ateng-boot-pay` 企业级多渠道支付专有收单与 Webhook 回调场景自验：微信支付 V3 / 支付宝双轨驱动、统一收单门面、服务端全自动二次加签、双轨防重并发锁与业务回调监听器 SPI。

---

## 1. 场景痛点与功能定位

`ateng-boot-pay` 是面向生产就绪的企业级支付中间件底座，坚守显式强类型契约、零实体泄露与全域 BigDecimal 元契约治理。`ateng-boot-sample-pay` 专注于纯净、端到端的收银台下单与异步回调管道自验：
- **纯内存离线自洽（100% Hermetic Testing）**：完全支持在脱机无公网环境下快速秒级跑通，无需连通微信或支付宝真实生产/沙箱服务器；
- **全场景收银台门面**：展示 Native（扫码支付）、JSAPI（小程序/公众号 5 元素调起）、H5（移动网页跳转）、App（移动端 SDK 加签调起串）、主动查单与主动关单；
- **传输层实体隔离与第一道防线**：Controller 统一接收 JSR-303 格式化 DTO，统一返回 `Result<PayResponse>`，Fail-Fast 拦截非法金额与空订单号；
- **业务回调监听器 SPI 落地**：演示实现 `PayCallbackListener`，基于内存/分布式锁防重放，幂等更新订单状态并向渠道返回确定性 `PayCallbackResult` ACK；
- **统一 Webhook 回调网关**：演示 `/ateng-pay/callback/{channel}/{merchantId}` 自动化验签、解密与状态机映射。

---

## 2. 场景架构与代码全景

| 场景组件 | 职责与覆盖核心门面 | 对应端到端测试 |
| :--- | :--- | :--- |
| `PayCheckoutController` | 统一下单收银控制器：暴露 Native、JSAPI、H5、App、查单、关单 REST 接口，返回 `Result<PayResponse>` | `PaySampleIntegrationTest` |
| `OrderPaymentCallbackListener` | 业务订单支付成功回调监听器：实现 `PayCallbackListener` SPI，演示幂等防重与确定性 ACK | `PaySampleIntegrationTest` |
| `PaySampleApplication` | 场景样例 Spring Boot 启动主类 | 容器启动生命周期 |
| `PaySampleIntegrationTest` | 100% 离线自洽 MockMvc 端到端契约测试（涵盖下单凭据、查单、关单、格式校验、回调幂等与网关 ACK） | `mvn test` 自动化验收 |

---

## 3. 核心接口快速自验范式

### 3.1 Native 扫码收单
- **请求路径**：`POST /pay/checkout/native`
- **请求报文**：
```json
{
  "channel": "WECHAT_PAY",
  "outTradeNo": "ORD_NATIVE_20261023_001",
  "amount": 99.90,
  "subject": "云端会员年度订阅"
}
```
- **响应报文**：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "channel": "WECHAT_PAY",
    "tradeType": "NATIVE",
    "outTradeNo": "ORD_NATIVE_20261023_001",
    "success": true,
    "qrCode": "weixin://wxpay/bizpayurl?pr=mockQrCode999"
  },
  "success": true,
  "traceId": "c8a14b0365734fb4864f7bdf753b890a"
}
```

### 3.2 JSAPI 调起收单 (小程序 / 公众号支付)
- **请求路径**：`POST /pay/checkout/jsapi`
- **响应报文**（服务端全自动完成 RSA 二次加签并封包 5 元素，前端直接调用 `wx.requestPayment`）：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "channel": "WECHAT_PAY",
    "tradeType": "JSAPI",
    "outTradeNo": "ORD_JSAPI_20261023_002",
    "prepayId": "wx20261023prepay001",
    "success": true,
    "wechatJsapi": {
      "appId": "wx8888888888888888",
      "timeStamp": "1729699200",
      "nonceStr": "mockNonceStr123",
      "packageVal": "prepay_id=wx20261023prepay001",
      "signType": "RSA",
      "paySign": "MOCK_PAY_SIGN_BASE64_VALUE"
    }
  },
  "success": true,
  "traceId": "c8a14b0365734fb4864f7bdf753b890a"
}
```

### 3.3 统一异步 Webhook 回调网关
- **微信回调端点**：`POST /ateng-pay/callback/wechat/{merchantId}`
- **支付宝回调端点**：`POST /ateng-pay/callback/alipay/{merchantId}`
- 网关全自动完成标头验签、报文解密、并发防重排队（`PayLockManager`）并派发给 `PayCallbackListener`，业务只需关注业务状态流转并返回 `PayCallbackResult`。

---

## 4. 本地端到端自验执行

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-pay -am -T 1C -nsu -B
```
