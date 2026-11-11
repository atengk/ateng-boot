# ateng-boot-sample-statemachine 业务状态机体验工程

> 专注于演示与自验 `ateng-boot-statemachine` 核心特性在电商订单全生命周期场景下的开箱使用：外部主干跃迁、内部自跃迁追加物流、Choice 动态风控条件分流、多源取消/退款汇聚、状态节点 Entry/Exit 生命周期、零副作用只读预检与动态按钮权限、CAS-First 乐观锁并发防重、声明式幂等放行、事务提交后不可逆通知、大批量切片关单与 Mermaid/PlantUML 动态拓扑导出。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleStateMachineApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-statemachine
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：基于 H2 纯内存数据库与离线自洽配置，启动即自动加载 `schema.sql` 完成表结构初始化，无需任何外部数据库或物理中间件依赖即可秒级启动与执行测试；
- **Local 真实模式**：若需切换至本地真实 MySQL / PostgreSQL 环境，可在 `application.yml` 中配置真实数据源连接串（或通过系统环境变量/启动参数覆盖），启动后即可通过标准 HTTP/cURL 接口进行全场景端点交互体验。

---

## 3. 核心 Controller 接口与一键体验

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **创建初始待支付订单** | `StateMachineOrderController` | `POST /sample/statemachine/order/create` | `curl -s -X POST "http://localhost:8080/sample/statemachine/order/create" -H "Content-Type: application/json" -d '{"orderSn": "SO202610100001", "amount": 199.00}'` |
| **查询订单当前详情与状态** | `StateMachineOrderController` | `GET /sample/statemachine/order/{orderId}` | `curl -s "http://localhost:8080/sample/statemachine/order/1"` |
| **买家付款外部主干跃迁** | `StateMachineOrderController` | `POST /sample/statemachine/order/{orderId}/pay` | `curl -s -X POST "http://localhost:8080/sample/statemachine/order/1/pay"` |
| **风控评估 Choice 分支路由 (正常放行)** | `StateMachineOrderController` | `POST /sample/statemachine/order/risk-audit` | `curl -s -X POST "http://localhost:8080/sample/statemachine/order/risk-audit" -H "Content-Type: application/json" -d '{"orderId": 1, "riskScore": 20}'` |
| **风控评估 Choice 分支路由 (高危拦截)** | `StateMachineOrderController` | `POST /sample/statemachine/order/risk-audit` | `curl -s -X POST "http://localhost:8080/sample/statemachine/order/risk-audit" -H "Content-Type: application/json" -d '{"orderId": 1, "riskScore": 95}'` |
| **商家发货外部主干跃迁** | `StateMachineOrderController` | `POST /sample/statemachine/order/ship` | `curl -s -X POST "http://localhost:8080/sample/statemachine/order/ship" -H "Content-Type: application/json" -d '{"orderId": 1, "trackingNo": "SF100200300"}'` |
| **内部自跃迁追加物流信息** | `StateMachineOrderController` | `POST /sample/statemachine/order/add-tracking` | `curl -s -X POST "http://localhost:8080/sample/statemachine/order/add-tracking" -H "Content-Type: application/json" -d '{"orderId": 1, "trackingNo": "SF100200300", "trackingRemark": "快件已到达北京转运中心"}'` |
| **买家妥投签收外部跃迁** | `StateMachineOrderController` | `POST /sample/statemachine/order/{orderId}/confirm-delivery` | `curl -s -X POST "http://localhost:8080/sample/statemachine/order/1/confirm-delivery"` |
| **交易完成归档外部跃迁** | `StateMachineOrderController` | `POST /sample/statemachine/order/{orderId}/finish` | `curl -s -X POST "http://localhost:8080/sample/statemachine/order/1/finish"` |
| **多源汇聚取消订单** | `StateMachineOrderController` | `POST /sample/statemachine/order/{orderId}/cancel` | `curl -s -X POST "http://localhost:8080/sample/statemachine/order/1/cancel"` |
| **多源汇聚申请退款** | `StateMachineOrderController` | `POST /sample/statemachine/order/{orderId}/apply-refund` | `curl -s -X POST "http://localhost:8080/sample/statemachine/order/1/apply-refund"` |
| **零副作用事件可行性探测** | `StateMachineProbeController` | `POST /sample/statemachine/probe/can-fire` | `curl -s -X POST "http://localhost:8080/sample/statemachine/probe/can-fire" -H "Content-Type: application/json" -d '{"orderId": 1, "event": "PAY"}'` |
| **只读推演目标状态与卫语句** | `StateMachineProbeController` | `POST /sample/statemachine/probe/verify` | `curl -s -X POST "http://localhost:8080/sample/statemachine/probe/verify" -H "Content-Type: application/json" -d '{"orderId": 1, "event": "PAY"}'` |
| **反查订单当前可用事件列表** | `StateMachineProbeController` | `GET /sample/statemachine/probe/{orderId}/available-events` | `curl -s "http://localhost:8080/sample/statemachine/probe/1/available-events"` |
| **评估前端动态按钮操作权限** | `StateMachineProbeController` | `GET /sample/statemachine/probe/{orderId}/button-actions` | `curl -s "http://localhost:8080/sample/statemachine/probe/1/button-actions"` |
| **第三方支付回调 (带CAS与幂等)** | `StateMachineCallbackController` | `POST /sample/statemachine/callback/payment` | `curl -s -X POST "http://localhost:8080/sample/statemachine/callback/payment" -H "Content-Type: application/json" -d '{"orderId": 1, "paidAmount": 199.00, "channelTxNo": "WX_TX_20261010001"}'` |
| **模拟高并发重复支付回调冲击** | `StateMachineCallbackController` | `POST /sample/statemachine/callback/simulate-concurrent` | `curl -s -X POST "http://localhost:8080/sample/statemachine/callback/simulate-concurrent?orderId=1&threads=5"` |
| **大批量超时订单切片批量关单** | `StateMachineBatchController` | `POST /sample/statemachine/batch/cancel-expired` | `curl -s -X POST "http://localhost:8080/sample/statemachine/batch/cancel-expired" -H "Content-Type: application/json" -d '{"orderIds": [1, 2, 3], "cancelReason": "超时未支付自动关单", "batchSize": 50}'` |
| **模拟大批量切片部分失败容错** | `StateMachineBatchController` | `POST /sample/statemachine/batch/simulate-partial-failure` | `curl -s -X POST "http://localhost:8080/sample/statemachine/batch/simulate-partial-failure"` |
| **导出动态 Mermaid 拓扑图源码** | `StateMachineTopologyController` | `GET /sample/statemachine/topology/mermaid` | `curl -s "http://localhost:8080/sample/statemachine/topology/mermaid"` |
| **导出动态 PlantUML 拓扑图源码** | `StateMachineTopologyController` | `GET /sample/statemachine/topology/plantuml` | `curl -s "http://localhost:8080/sample/statemachine/topology/plantuml"` |
| **探查全局已注册机台标识集合** | `StateMachineTopologyController` | `GET /sample/statemachine/topology/machines` | `curl -s "http://localhost:8080/sample/statemachine/topology/machines"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-statemachine,ateng-boot-samples/ateng-boot-sample-statemachine -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-statemachine README](../../ateng-boot-statemachine/README.md)。
