# ateng-boot-sample-observability 云原生可观测性场景体验工程

> 专注于演示与自验 `ateng-boot-observability` 核心特性在真实电商交易与履约业务场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleObservabilityApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-observability
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置离线 H2 内存数据库与本地临时目录存储驱动，无需外部依赖即可启动与跑通端到端自验；
- **Local 真实模式**：若需连接真实 MySQL 或 Redis 进行连通性探测，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实连接信息（已被 Git 忽略，CI 零风险），激活 `local` Profile 启动即可直连测试。

---

## 3. 核心 Controller 接口与一键体验

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **编程式指标打点 (Counter/Summary/Timer)** | `ObservabilityMetricsController` | `POST /sample/observability/metrics/order-create` | `curl -X POST "http://localhost:8080/sample/observability/metrics/order-create" -H "Content-Type: application/json" -d "{\"orderId\":\"ORD-20261110-001\",\"amount\":99.5,\"channel\":\"app\"}"` |
| **声明式方法耗时监控 (`@ObservedMetric`)** | `ObservabilityMetricsController` | `POST /sample/observability/metrics/stock-deduct` | `curl -X POST "http://localhost:8080/sample/observability/metrics/stock-deduct" -H "Content-Type: application/json" -d "{\"productId\":\"PROD-8888\",\"count\":2}"` |
| **在线商户 Gauge 采集** | `ObservabilityMetricsController` | `GET /sample/observability/metrics/merchant-active-count` | `curl -X GET "http://localhost:8080/sample/observability/metrics/merchant-active-count"` |
| **订单风控校验 (`record(Supplier)`)** | `ObservabilityMetricsController` | `POST /sample/observability/metrics/risk-check` | `curl -X POST "http://localhost:8080/sample/observability/metrics/risk-check" -H "Content-Type: application/json" -d "{\"orderId\":\"ORD-20261116-101\",\"amount\":288.0,\"channel\":\"app\"}"` |
| **分段履约计时 (`startSample/stopSample`)** | `ObservabilityMetricsController` | `POST /sample/observability/metrics/fulfillment-track` | `curl -X POST "http://localhost:8080/sample/observability/metrics/fulfillment-track" -H "Content-Type: application/json" -d "{\"orderId\":\"ORD-20261116-101\",\"warehouseCode\":\"WH-SH-01\"}"` |
| **队列与缓存容量 Gauge (`Collection/Map`)** | `ObservabilityMetricsController` | `GET /sample/observability/metrics/capacity-gauges` | `curl -X GET "http://localhost:8080/sample/observability/metrics/capacity-gauges"` |
| **临时促销活动指标打点** | `ObservabilityMetricsController` | `POST /sample/observability/metrics/campaign-metric/record` | `curl -X POST "http://localhost:8080/sample/observability/metrics/campaign-metric/record" -H "Content-Type: application/json" -d "{\"campaignId\":\"FLASH-1116\",\"channel\":\"app\"}"` |
| **临时活动指标动态注销 (`remove`)** | `ObservabilityMetricsController` | `POST /sample/observability/metrics/campaign-metric/remove` | `curl -X POST "http://localhost:8080/sample/observability/metrics/campaign-metric/remove" -H "Content-Type: application/json" -d "{\"campaignId\":\"FLASH-1116\",\"channel\":\"app\"}"` |
| **直方图与 SLO 订单结算 (`histogram/slaMs`)** | `ObservabilityMetricsController` | `POST /sample/observability/metrics/order-settle` | `curl -X POST "http://localhost:8080/sample/observability/metrics/order-settle" -H "Content-Type: application/json" -d "{\"orderId\":\"ORD-20261116-101\",\"amount\":288.0,\"paymentMethod\":\"alipay\"}"` |
| **物流运单异步推单** | `ObservabilityThreadPoolController` | `POST /sample/observability/thread-pool/dispatch-logistics` | `curl -X POST "http://localhost:8080/sample/observability/thread-pool/dispatch-logistics" -H "Content-Type: application/json" -d "{\"trackId\":\"TRK-10001\"}"` |
| **线程池高压饱和拒绝** | `ObservabilityThreadPoolController` | `POST /sample/observability/thread-pool/trigger-burst` | `curl -X POST "http://localhost:8080/sample/observability/thread-pool/trigger-burst" -H "Content-Type: application/json" -d "{\"taskCount\":70}"` |
| **支付网关安全探针** | `ObservabilityProbeController` | `GET /sample/observability/probe/payment-gateway` | `curl -X GET "http://localhost:8080/sample/observability/probe/payment-gateway"` |
| **注入支付网关故障 (触发降级事件)** | `ObservabilityProbeController` | `POST /sample/observability/probe/payment-gateway/degrade` | `curl -X POST "http://localhost:8080/sample/observability/probe/payment-gateway/degrade?reason=MockGatewayDown"` |
| **恢复支付网关健康 (触发自愈事件)** | `ObservabilityProbeController` | `POST /sample/observability/probe/payment-gateway/recover` | `curl -X POST "http://localhost:8080/sample/observability/probe/payment-gateway/recover"` |
| **慢探针超时熔断降级** | `ObservabilityProbeController` | `GET /sample/observability/probe/simulate-timeout` | `curl -X GET "http://localhost:8080/sample/observability/probe/simulate-timeout"` |
| **白名单业务端点放行** | `ObservabilitySecurityController` | `GET /sample/observability/security/public-ping` | `curl -X GET "http://localhost:8080/sample/observability/security/public-ping"` |
| **加固敏感端点访问提示** | `ObservabilitySecurityController` | `GET /sample/observability/security/simulate-lockdown` | `curl -X GET "http://localhost:8080/sample/observability/security/simulate-lockdown"` |
| **SBA 客户端握手密钥拦截器自检** | `ObservabilitySecurityController` | `GET /sample/observability/security/admin-handshake-check` | `curl -X GET "http://localhost:8080/sample/observability/security/admin-handshake-check?clientSecret=test-sba-handshake-secret"` |
| **运维领域事件回溯查询** | `ObservabilitySecurityController` | `GET /sample/observability/security/recent-events` | `curl -X GET "http://localhost:8080/sample/observability/security/recent-events"` |
| **Prometheus 指标抓取** | Actuator 原生端点 | `GET /actuator/prometheus` | `curl -X GET "http://localhost:8080/actuator/prometheus"` |
| **K8s 存活探针 (黄金隔离)** | Actuator 原生端点 | `GET /actuator/health/liveness` | `curl -X GET "http://localhost:8080/actuator/health/liveness"` |
| **K8s 就绪探针 (外部聚合)** | Actuator 原生端点 | `GET /actuator/health/readiness` | `curl -X GET "http://localhost:8080/actuator/health/readiness"` |
| **加固端点 Token 鉴权** | Actuator 原生端点 | `GET /actuator/env` | `curl -X GET "http://localhost:8080/actuator/env" -H "X-Actuator-Token: obs-admin-secret-token"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-observability,ateng-boot-samples/ateng-boot-sample-observability -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-observability README](../../ateng-boot-observability/README.md)。
