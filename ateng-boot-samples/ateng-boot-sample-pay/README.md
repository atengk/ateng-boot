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
- **Local 真实模式**：若需连接微信支付或支付宝真实生产/沙箱环境，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实商户号、AppID 与私钥证书（保留大写占位符示例中的 `default-transfer-scene-id: "1000"` 及通道 HTTP `connect-timeout`/`read-timeout` 超时配置），激活 `local` Profile 启动即可直连测试（该文件已被 Git 忽略，CI 零风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 **34 组端点 cURL 命令**（涵盖 6 大业务领域控制器 32 组主动业务端点与 2 组 Webhook 回调仿真端点）直接发起调用体验：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **微信 Native 扫码收单** | `PayCheckoutController` | `POST /sample/pay/checkout/native` | `curl -X POST "http://localhost:8080/sample/pay/checkout/native" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outTradeNo":"ORD_NATIVE_20261118_001","amount":99.90,"subject":"云端会员年度订阅"}'` |
| **微信 JSAPI 小程序/公众号收单** | `PayCheckoutController` | `POST /sample/pay/checkout/jsapi` | `curl -X POST "http://localhost:8080/sample/pay/checkout/jsapi" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outTradeNo":"ORD_JSAPI_20261118_002","amount":12.50,"subject":"咖啡在线点单","openid":"oUpF8uMuAJO_M2pxb1Q9zNjWeS6o"}'` |
| **支付宝小程序统一收单创建 (JSAPI)** | `PayCheckoutController` | `POST /sample/pay/checkout/alipay-miniapp` | `curl -X POST "http://localhost:8080/sample/pay/checkout/alipay-miniapp" -H "Content-Type: application/json" -d '{"channel":"ALIPAY","outTradeNo":"ORD_ALI_MINI_20261118_003","amount":88.00,"subject":"支付宝小程序购买云服务","buyerId":"2088102123456789"}'` |
| **微信 H5 移动网页收单** | `PayCheckoutController` | `POST /sample/pay/checkout/h5` | `curl -X POST "http://localhost:8080/sample/pay/checkout/h5" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outTradeNo":"ORD_H5_20261118_004","amount":66.00,"subject":"H5移动充值"}'` |
| **支付宝 App 客户端调起收单** | `PayCheckoutController` | `POST /sample/pay/checkout/app` | `curl -X POST "http://localhost:8080/sample/pay/checkout/app" -H "Content-Type: application/json" -d '{"channel":"ALIPAY","outTradeNo":"ORD_APP_20261118_005","amount":199.00,"subject":"App端内会员升级"}'` |
| **订单交易状态主动查询** | `PayCheckoutController` | `POST /sample/pay/checkout/query` | `curl -X POST "http://localhost:8080/sample/pay/checkout/query" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outTradeNo":"ORD_NATIVE_20261118_001"}'` |
| **未付款订单主动关单** | `PayCheckoutController` | `POST /sample/pay/checkout/close` | `curl -X POST "http://localhost:8080/sample/pay/checkout/close" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outTradeNo":"ORD_NATIVE_20261118_001"}'` |
| **线下收银台付款码扣款与轮询** | `PayBarcodeController` | `POST /sample/pay/barcode/cashier` | `curl -X POST "http://localhost:8080/sample/pay/barcode/cashier" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outTradeNo":"ORD_BARCODE_20261118_006","amount":15.00,"authCode":"134567890123456789","subject":"线下门店收银台面对面扫码"}'` |
| **条码/超时订单主动撤销冲正** | `PayBarcodeController` | `POST /sample/pay/barcode/cancel` | `curl -X POST "http://localhost:8080/sample/pay/barcode/cancel" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outTradeNo":"ORD_CANCEL_WX_023"}'` |
| **售后退款申请 (支持部分退款)** | `PayRefundController` | `POST /sample/pay/refund/apply` | `curl -X POST "http://localhost:8080/sample/pay/refund/apply" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outTradeNo":"ORD_NATIVE_20261118_001","outRefundNo":"REF_20261118_001","refundAmount":20.00,"totalAmount":99.90,"reason":"用户申请部分退款"}'` |
| **售后退款状态进度查询** | `PayRefundController` | `POST /sample/pay/refund/query` | `curl -X POST "http://localhost:8080/sample/pay/refund/query" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outRefundNo":"REF_20261118_001"}'` |
| **单笔资金转账申请 (微信新版/支付宝)** | `PayTransferController` | `POST /sample/pay/transfer/apply` | `curl -X POST "http://localhost:8080/sample/pay/transfer/apply" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outBizNo":"MCH_SAMPLE_TRANSFER_024","amount":200.00,"title":"合伙人补贴发放","remark":"2026-11 奖励补贴","payeeAccount":"oUpF8uMuAJO_M2pxb1Q9zNjWeS6o","payeeRealName":"张三","transferSceneId":"1000","transferSceneReportInfos":[{"infoType":"活动名称","infoContent":"渠道拉新奖励"}]}'` |
| **单笔资金转账状态查询** | `PayTransferController` | `POST /sample/pay/transfer/query` | `curl -X POST "http://localhost:8080/sample/pay/transfer/query" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outBizNo":"MCH_SAMPLE_TRANSFER_024"}'` |
| **待确认商家转账主动撤销** | `PayTransferController` | `POST /sample/pay/transfer/cancel` | `curl -X POST "http://localhost:8080/sample/pay/transfer/cancel" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","outBizNo":"MCH_SAMPLE_TRANSFER_024"}'` |
| **微信分账：添加接收方** | `PayProfitSharingController` | `POST /sample/pay/profit-sharing/receivers/add` | `curl -X POST "http://localhost:8080/sample/pay/profit-sharing/receivers/add" -H "Content-Type: application/json" -d '{"type":"PERSONAL_OPENID","account":"oUpF8uMuAJO_M2pxb1Q9zNjWeS6o","relationType":"USER"}'` |
| **微信分账：删除接收方** | `PayProfitSharingController` | `POST /sample/pay/profit-sharing/receivers/delete` | `curl -X POST "http://localhost:8080/sample/pay/profit-sharing/receivers/delete" -H "Content-Type: application/json" -d '{"type":"PERSONAL_OPENID","account":"oUpF8uMuAJO_M2pxb1Q9zNjWeS6o"}'` |
| **微信分账：发起分账请求** | `PayProfitSharingController` | `POST /sample/pay/profit-sharing/orders/create` | `curl -X POST "http://localhost:8080/sample/pay/profit-sharing/orders/create" -H "Content-Type: application/json" -d '{"transactionId":"TRANSACTION_20261118_025","outOrderNo":"PS_SAMPLE_025","unfreezeUnsplit":false,"receivers":[{"type":"PERSONAL_OPENID","account":"oUpF8uMuAJO_M2pxb1Q9zNjWeS6o","amount":100,"description":"门店推广佣金"}]}'` |
| **微信分账：查询分账结果** | `PayProfitSharingController` | `POST /sample/pay/profit-sharing/orders/query` | `curl -X POST "http://localhost:8080/sample/pay/profit-sharing/orders/query" -H "Content-Type: application/json" -d '{"outOrderNo":"PS_SAMPLE_025","transactionId":"TRANSACTION_20261118_025"}'` |
| **微信分账：完结解冻剩余资金** | `PayProfitSharingController` | `POST /sample/pay/profit-sharing/unfreeze` | `curl -X POST "http://localhost:8080/sample/pay/profit-sharing/unfreeze" -H "Content-Type: application/json" -d '{"outOrderNo":"ORD_UNFREEZE_20261118_001","transactionId":"TRANSACTION_20261118_025","description":"结算分账完毕，解冻在途资金"}'` |
| **微信分账：发起分账回退** | `PayProfitSharingController` | `POST /sample/pay/profit-sharing/return-orders/create` | `curl -X POST "http://localhost:8080/sample/pay/profit-sharing/return-orders/create" -H "Content-Type: application/json" -d '{"outOrderNo":"PS_SAMPLE_025","outReturnNo":"RET_SAMPLE_025","returnMchid":"1900000002","amount":100,"description":"退款触发分账回退"}'` |
| **微信分账：查询分账回退结果** | `PayProfitSharingController` | `POST /sample/pay/profit-sharing/return-orders/query` | `curl -X POST "http://localhost:8080/sample/pay/profit-sharing/return-orders/query" -H "Content-Type: application/json" -d '{"outReturnNo":"RET_SAMPLE_025","outOrderNo":"PS_SAMPLE_025"}'` |
| **微信分账：查询订单剩余待分金额** | `PayProfitSharingController` | `POST /sample/pay/profit-sharing/amounts/query` | `curl -X POST "http://localhost:8080/sample/pay/profit-sharing/amounts/query" -H "Content-Type: application/json" -d '{"transactionId":"TRANSACTION_20261118_025"}'` |
| **微信分账：查询子商户最大分账比例** | `PayProfitSharingController` | `POST /sample/pay/profit-sharing/merchant-ratios/query` | `curl -X POST "http://localhost:8080/sample/pay/profit-sharing/merchant-ratios/query" -H "Content-Type: application/json" -d '{"subMchid":"1900000002"}'` |
| **支付宝分账：前置冻结扫码预下单** | `PayProfitSharingController` | `POST /sample/pay/profit-sharing/alipay/pre-freeze-qr` | `curl -X POST "http://localhost:8080/sample/pay/profit-sharing/alipay/pre-freeze-qr" -H "Content-Type: application/json" -d '{"outTradeNo":"ALI_FRZ_026","amount":99.00,"subject":"前置冻结分账商品","royaltyFreeze":true,"appAuthToken":"YOUR_ISV_APP_AUTH_TOKEN"}'` |
| **支付宝分账：绑定分账关系** | `PayProfitSharingController` | `POST /sample/pay/profit-sharing/alipay/receivers/bind` | `curl -X POST "http://localhost:8080/sample/pay/profit-sharing/alipay/receivers/bind" -H "Content-Type: application/json" -d '{"outRequestNo":"ALI_BIND_026","receivers":[{"type":"userId","account":"2088101126708402","name":"合作方A","memo":"供应商分成"}]}'` |
| **支付宝分账：解绑分账关系** | `PayProfitSharingController` | `POST /sample/pay/profit-sharing/alipay/receivers/unbind` | `curl -X POST "http://localhost:8080/sample/pay/profit-sharing/alipay/receivers/unbind" -H "Content-Type: application/json" -d '{"outRequestNo":"ALI_BIND_026","receivers":[{"type":"userId","account":"2088101126708402","name":"合作方A"}]}'` |
| **支付宝分账：批量查询分账关系** | `PayProfitSharingController` | `POST /sample/pay/profit-sharing/alipay/receivers/batchquery` | `curl -X POST "http://localhost:8080/sample/pay/profit-sharing/alipay/receivers/batchquery" -H "Content-Type: application/json" -d '{"outRequestNo":"ALI_QUERY_REL_026","pageNum":1,"pageSize":20}'` |
| **支付宝分账：交易结算确认** | `PayProfitSharingController` | `POST /sample/pay/profit-sharing/alipay/settle/confirm` | `curl -X POST "http://localhost:8080/sample/pay/profit-sharing/alipay/settle/confirm" -H "Content-Type: application/json" -d '{"outRequestNo":"ALI_CONFIRM_026","tradeNo":"202611182200140026","amount":99.00,"settlePeriodTime":"1d"}'` |
| **支付宝分账：统一收单分账结算** | `PayProfitSharingController` | `POST /sample/pay/profit-sharing/alipay/settle/order` | `curl -X POST "http://localhost:8080/sample/pay/profit-sharing/alipay/settle/order" -H "Content-Type: application/json" -d '{"outRequestNo":"ALI_SETTLE_026","tradeNo":"202611182200140026","royaltyParameters":[{"transInType":"userId","transIn":"2088101126708402","amount":19.80,"desc":"渠道合作分账"}]}'` |
| **支付宝分账：分账进度查询** | `PayProfitSharingController` | `POST /sample/pay/profit-sharing/alipay/settle/query` | `curl -X POST "http://localhost:8080/sample/pay/profit-sharing/alipay/settle/query" -H "Content-Type: application/json" -d '{"settleNo":"20261118SETTLE026","outRequestNo":"ALI_SETTLE_026"}'` |
| **财务对账单流式全量内存解析** | `PayBillController` | `POST /sample/pay/bill/download/parse-memory` | `curl -X POST "http://localhost:8080/sample/pay/bill/download/parse-memory" -H "Content-Type: application/json" -d '{"channel":"ALIPAY","billDate":"2026-11-18","billType":"trade"}'` |
| **财务对账单零 OOM 流式对账消费** | `PayBillController` | `POST /sample/pay/bill/download/stream-consume` | `curl -X POST "http://localhost:8080/sample/pay/bill/download/stream-consume" -H "Content-Type: application/json" -d '{"channel":"WECHAT_PAY","billDate":"2026-10-24","billType":"ALL","tarType":"GZIP"}'` |
| **统一异步 Webhook 回调仿真 (微信)** | `PayWebhookController` | `POST /ateng-pay/callback/wechat/{merchantId}` | `curl -X POST "http://localhost:8080/ateng-pay/callback/wechat/default" -H "Content-Type: application/json" -H "Wechatpay-Serial: PUB_KEY_ID_TEST_001" -H "Wechatpay-Signature: mock" -H "Wechatpay-Timestamp: 1600000000" -H "Wechatpay-Nonce: mock" -d '{"id":"EVT_TEST","event_type":"TRANSACTION.SUCCESS","resource":{"algorithm":"AEAD_AES_256_GCM"}}'` |
| **统一异步 Webhook 回调仿真 (支付宝)** | `PayWebhookController` | `POST /ateng-pay/callback/alipay/{merchantId}` | `curl -X POST "http://localhost:8080/ateng-pay/callback/alipay/default" -H "Content-Type: application/x-www-form-urlencoded" -d "trade_status=TRADE_SUCCESS&out_trade_no=ORD_ALI_001&total_amount=88.00&sign=mock&sign_type=RSA2"` |

---

## 4. 全真自动化回归命令

```bash
# 运行核心模块单测与 Sample 全部 30 项端到端 MockMvc + MockWebServer 集成测试
mvn test -pl ateng-boot-pay,ateng-boot-samples/ateng-boot-sample-pay -am -T 1C -nsu -B
```

> 详细原理机制、全景速查矩阵、配置字典与扩展定制，请参阅核心 Starter 文档：[ateng-boot-pay README](../../ateng-boot-pay/README.md)。
