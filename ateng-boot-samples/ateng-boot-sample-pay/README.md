# ateng-boot-sample-pay 企业级多渠道支付场景自验工程

> 专注于演示与自验 `ateng-boot-pay` 核心特性在真实业务场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SamplePayApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-pay
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置离线仿真测试桩与自洽配置（基于纯内存 OkHttp `MockWebServer` 拦截出站请求驱动腾讯微信与蚂蚁金服官方 SDK 真实协议握手，零外部公网依赖），无需配置任何真实证书与密钥即可秒级启动运行与 100% 跑通测试；
- **Local 真实模式**：若需连接微信支付或支付宝真实生产/沙箱环境，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实商户号、AppID 与私钥证书，激活 `local` Profile 启动即可直连测试（该文件已被 Git 忽略，CI 零风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 18 组端点 cURL 命令（涵盖 6 大控制器 16 组主动业务端点与 2 组 Webhook 回调仿真端点）直接发起调用体验：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **微信 Native 扫码收单** | `PayCheckoutController` | `POST /sample/pay/checkout/native` | `curl -X POST "http://localhost:8080/sample/pay/checkout/native" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outTradeNo":"ORD_NATIVE_20261110_001","amount":99.90,"subject":"云端会员年度订阅"}'` |
| **微信 JSAPI 小程序/公众号收单** | `PayCheckoutController` | `POST /sample/pay/checkout/jsapi` | `curl -X POST "http://localhost:8080/sample/pay/checkout/jsapi" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outTradeNo":"ORD_JSAPI_20261110_002","amount":12.50,"subject":"咖啡在线点单","openid":"oUpF8uMuAJO_M2pxb1Q9zNjWeS6o"}'` |
| **支付宝小程序统一收单创建 (JSAPI)** | `PayCheckoutController` | `POST /sample/pay/checkout/alipay-miniapp` | `curl -X POST "http://localhost:8080/sample/pay/checkout/alipay-miniapp" -H "Content-Type: application/json" -d '{"outTradeNo":"ORD_ALI_MINI_20261110_003","amount":88.00,"subject":"支付宝小程序购买云服务","buyerId":"2088102123456789"}'` |
| **微信 H5 移动网页收单** | `PayCheckoutController` | `POST /sample/pay/checkout/h5` | `curl -X POST "http://localhost:8080/sample/pay/checkout/h5" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outTradeNo":"ORD_H5_20261110_004","amount":66.00,"subject":"H5移动充值"}'` |
| **支付宝 App 客户端调起收单** | `PayCheckoutController` | `POST /sample/pay/checkout/app` | `curl -X POST "http://localhost:8080/sample/pay/checkout/app" -H "Content-Type: application/json" -d '{"channel":"ALIPAY","outTradeNo":"ORD_APP_20261110_005","amount":199.00,"subject":"App端内会员升级"}'` |
| **订单交易状态主动查询** | `PayCheckoutController` | `POST /sample/pay/checkout/query` | `curl -X POST "http://localhost:8080/sample/pay/checkout/query" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outTradeNo":"ORD_NATIVE_20261110_001"}'` |
| **未付款订单主动关单** | `PayCheckoutController` | `POST /sample/pay/checkout/close` | `curl -X POST "http://localhost:8080/sample/pay/checkout/close" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outTradeNo":"ORD_NATIVE_20261110_001"}'` |
| **线下收银台付款码扣款与轮询** | `PayBarcodeController` | `POST /sample/pay/barcode/cashier` | `curl -X POST "http://localhost:8080/sample/pay/barcode/cashier" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outTradeNo":"ORD_BARCODE_20261110_006","amount":15.00,"authCode":"134567890123456789","subject":"线下门店收银台面对面扫码"}'` |
| **售后退款申请 (支持部分退款)** | `PayRefundController` | `POST /sample/pay/refund/apply` | `curl -X POST "http://localhost:8080/sample/pay/refund/apply" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outTradeNo":"ORD_NATIVE_20261110_001","outRefundNo":"REF_20261110_001","refundAmount":20.00,"totalAmount":99.90,"reason":"用户申请部分退款"}'` |
| **售后退款状态进度查询** | `PayRefundController` | `POST /sample/pay/refund/query` | `curl -X POST "http://localhost:8080/sample/pay/refund/query" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outRefundNo":"REF_20261110_001"}'` |
| **单笔资金转账申请 (姓名账号脱敏)** | `PayTransferController` | `POST /sample/pay/transfer/apply` | `curl -X POST "http://localhost:8080/sample/pay/transfer/apply" -H "Content-Type: application/json" -d '{"channel":"ALIPAY","outBizNo":"TRANS_BIZ_20261110_001","amount":50.00,"title":"佣金提现结算","payeeAccount":"alipay_user@example.com","payeeRealName":"张三"}'` |
| **单笔资金转账状态查询** | `PayTransferController` | `POST /sample/pay/transfer/query` | `curl -X POST "http://localhost:8080/sample/pay/transfer/query" -H "Content-Type: application/json" -d '{"channel":"ALIPAY","outBizNo":"TRANS_BIZ_20261110_001"}'` |
| **添加微信资金分账接收方** | `PayProfitSharingController` | `POST /sample/pay/profit-sharing/receivers/add` | `curl -X POST "http://localhost:8080/sample/pay/profit-sharing/receivers/add" -H "Content-Type: application/json" -d '{"type":"PERSONAL_OPENID","account":"oUpF8uMuAJO_M2pxb1Q9zNjWeS6o","relationType":"USER"}'` |
| **完结分账解冻在途剩余资金** | `PayProfitSharingController` | `POST /sample/pay/profit-sharing/unfreeze` | `curl -X POST "http://localhost:8080/sample/pay/profit-sharing/unfreeze" -H "Content-Type: application/json" -d '{"outOrderNo":"ORD_UNFREEZE_20261110_001","transactionId":"TRANSACTION_20261110_001","description":"结算分账完毕，解冻在途资金"}'` |
| **财务对账单流式全量内存解析** | `PayBillController` | `POST /sample/pay/bill/download/parse-memory` | `curl -X POST "http://localhost:8080/sample/pay/bill/download/parse-memory" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","billDate":"2026-10-24","billType":"ALL","tarType":"GZIP"}'` |
| **财务对账单零 OOM 流式对账消费** | `PayBillController` | `POST /sample/pay/bill/download/stream-consume` | `curl -X POST "http://localhost:8080/sample/pay/bill/download/stream-consume" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","billDate":"2026-10-24","billType":"ALL","tarType":"GZIP"}'` |
| **统一异步 Webhook 回调仿真 (微信)** | Starter 网关端点 | `POST /ateng-pay/callback/wechat/{merchantId}` | `curl -X POST "http://localhost:8080/ateng-pay/callback/wechat/default" -H "Content-Type: application/json" -H "Wechatpay-Serial: PUB_KEY_ID_TEST_001" -H "Wechatpay-Signature: mock" -H "Wechatpay-Timestamp: 1600000000" -H "Wechatpay-Nonce: mock" -d '{"id":"EVT_TEST","resource":{"algorithm":"AEAD_AES_256_GCM"}}'` |
| **统一异步 Webhook 回调仿真 (支付宝)** | Starter 网关端点 | `POST /ateng-pay/callback/alipay/{merchantId}` | `curl -X POST "http://localhost:8080/ateng-pay/callback/alipay/default" -H "Content-Type: application/x-www-form-urlencoded" -d "trade_status=TRADE_SUCCESS&out_trade_no=ORD_ALI_001&total_amount=88.00&sign=mock&sign_type=RSA2"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-pay,ateng-boot-samples/ateng-boot-sample-pay -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-pay README](../../ateng-boot-pay/README.md)。
