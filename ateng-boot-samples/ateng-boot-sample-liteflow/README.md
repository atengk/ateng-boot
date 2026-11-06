# ateng-boot-sample-liteflow 规则编排与流程管道场景自验工程

> 专注于演示与自验 `ateng-boot-liteflow` 核心特性在真实业务场景下的开箱使用，包含电商复合营销算价、金融信贷多维风控审批、订单履约流转与 Saga 逆序反向补偿、在线即时 EL 表达式执行与 Spring 领域事件热更总线。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleLiteflowApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-liteflow
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：原生具备 100% 离线自洽规则与内存级执行能力，预置 `price-flow.el.xml`、`risk-flow.el.xml`、`fulfillment-flow.el.xml`、`dynamic-flow.el.xml`、`promotion-routing-flow.el.xml` 规则，无需依赖任何外部物理中间件或数据库即可极速启动与运行；
- **Local 真实模式**：若需开启本地扩展配置或自定义参数覆盖，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml`，启动自动生效（该文件已被 `.gitignore` 严格忽略，CI 构建零风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令直接发起调用体验：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **电商复合营销算价** | `LiteflowPriceCalculationController` | `POST /sample/liteflow/price/calculate` | `curl -X POST "http://localhost:8080/sample/liteflow/price/calculate" -H "Content-Type: application/json" -d "{\"orderId\":\"ORD_20261103_001\",\"userId\":\"user_svip_888\",\"originalAmount\":200.00,\"couponId\":\"COUPON_50\"}"` |
| **信贷多维风控审批** | `LiteflowRiskControlController` | `POST /sample/liteflow/risk/evaluate` | `curl -X POST "http://localhost:8080/sample/liteflow/risk/evaluate" -H "Content-Type: application/json" -d "{\"applicantId\":\"app_user_001\",\"idCard\":\"110101199001011234\",\"applyAmount\":50000.00,\"creditScore\":780}"` |
| **订单履约流转 (正常)** | `LiteflowOrderFulfillmentController` | `POST /sample/liteflow/fulfillment/fulfill` | `curl -X POST "http://localhost:8080/sample/liteflow/fulfillment/fulfill" -H "Content-Type: application/json" -d "{\"orderId\":\"ORD_20261103_002\",\"skuId\":\"SKU_IPHONE_16\",\"quantity\":1,\"simulateFailure\":false}"` |
| **Saga 逆向补偿体验** | `LiteflowOrderFulfillmentController` | `POST /sample/liteflow/fulfillment/fulfill` | `curl -X POST "http://localhost:8080/sample/liteflow/fulfillment/fulfill" -H "Content-Type: application/json" -d "{\"orderId\":\"ORD_20261103_003\",\"skuId\":\"SKU_MACBOOK_PRO\",\"quantity\":1,\"simulateFailure\":true}"` |
| **探测规则链就绪状态** | `LiteflowDynamicRuleController` | `GET /sample/liteflow/dynamic/contain-chain` | `curl -X GET "http://localhost:8080/sample/liteflow/dynamic/contain-chain?chainId=price-calc-flow"` |
| **在线即时 EL 表达式执行** | `LiteflowDynamicRuleController` | `POST /sample/liteflow/dynamic/execute-el` | `curl -X POST "http://localhost:8080/sample/liteflow/dynamic/execute-el" -H "Content-Type: application/json" -d "{\"el\":\"THEN(dynamicValidateCmp, dynamicEchoCmp);\",\"payload\":\"Antigravity-LiteFlow\"}"` |
| **动态规则热重载** | `LiteflowDynamicRuleController` | `POST /sample/liteflow/dynamic/reload-chain` | `curl -X POST "http://localhost:8080/sample/liteflow/dynamic/reload-chain" -H "Content-Type: application/json" -d "{\"chainId\":\"dynamic-custom-chain\",\"el\":\"THEN(dynamicValidateCmp, dynamicTransformCmp);\"}"` |
| **发布领域事件热更总线** | `LiteflowDynamicRuleController` | `POST /sample/liteflow/dynamic/refresh-event` | `curl -X POST "http://localhost:8080/sample/liteflow/dynamic/refresh-event?chainId=price-calc-flow&el=THEN(validateOrderCmp,calculatePostageCmp);"` |
| **促销决策路由与上下文聚合** | `LiteflowPromotionRoutingController` | `POST /sample/liteflow/routing/match-promotions` | `curl -X POST "http://localhost:8080/sample/liteflow/routing/match-promotions" -H "Content-Type: application/json" -d "{\"userId\":\"VIP_USER_101\",\"userLevel\":\"VIP\",\"cartAmount\":450.00,\"category\":\"DIGITAL\"}"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-liteflow,ateng-boot-samples/ateng-boot-sample-liteflow -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-liteflow README](../../ateng-boot-liteflow/README.md)。
