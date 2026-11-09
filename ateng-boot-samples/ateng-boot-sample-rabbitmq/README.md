# ateng-boot-sample-rabbitmq 生产级路由与可靠投递体验工程

> 专注于演示与自验 `ateng-boot-rabbitmq` 生产级增强 Starter 在真实业务场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleRabbitmqApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-rabbitmq
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置 `@Profile("!local")` 离线模拟桩（`TestAmqpConfig`）与内存自洽配置，无需外部 RabbitMQ 依赖即可快速启动与自动化端到端测试；
- **Local 真实模式**：若需连接真实 RabbitMQ Broker，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实 Broker 连接信息（该文件已被 `.gitignore` 强制忽略，CI 零泄露风险），激活 `local` Profile 启动即可直连真实集群联调。

---

## 3. 核心 Controller 接口与一键体验

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **同步可靠投递订单** | `RabbitProduceController` | `POST /sample/rabbitmq/produce/order` | `curl -X POST "http://localhost:8080/sample/rabbitmq/produce/order" -H "Content-Type: application/json" -d '{"orderId":"ORD-1001","amount":188.00,"userId":"user-01"}'` |
| **异步非阻塞可靠投递** | `RabbitProduceController` | `POST /sample/rabbitmq/produce/order-async` | `curl -X POST "http://localhost:8080/sample/rabbitmq/produce/order-async" -H "Content-Type: application/json" -d '{"orderId":"ORD-ASYNC-1002","amount":299.90,"userId":"user-02"}'` |
| **Topic 层次通配投递日志** | `RabbitProduceController` | `POST /sample/rabbitmq/produce/log` | `curl -X POST "http://localhost:8080/sample/rabbitmq/produce/log" -H "Content-Type: application/json" -d '{"routingKey":"log.order.error","level":"ERROR","serviceName":"order-service","content":"库存扣减失败"}'` |
| **不可路由消息 Returns 告警** | `RabbitProduceController` | `POST /sample/rabbitmq/produce/unroutable` | `curl -X POST "http://localhost:8080/sample/rabbitmq/produce/unroutable?payload=test-unroutable-data"` |
| **探查订单消费记录** | `RabbitProduceController` | `GET /sample/rabbitmq/produce/received-orders` | `curl -X GET "http://localhost:8080/sample/rabbitmq/produce/received-orders"` |
| **本地事务提交物理发信** | `RabbitTransactionController` | `POST /sample/rabbitmq/transaction/commit` | `curl -X POST "http://localhost:8080/sample/rabbitmq/transaction/commit" -H "Content-Type: application/json" -d '{"orderId":"ORD-TX-COMMIT-01","amount":99.00,"userId":"user-tx"}'` |
| **本地事务异常回滚零发信** | `RabbitTransactionController` | `POST /sample/rabbitmq/transaction/rollback` | `curl -X POST "http://localhost:8080/sample/rabbitmq/transaction/rollback" -H "Content-Type: application/json" -d '{"orderId":"ORD-TX-ROLLBACK-01","amount":99.00,"userId":"user-tx"}'` |
| **微批订单同步聚合投递** | `RabbitBatchAndPriorityController` | `POST /sample/rabbitmq/batch/orders` | `curl -X POST "http://localhost:8080/sample/rabbitmq/batch/orders" -H "Content-Type: application/json" -d '{"orders":[{"orderId":"BATCH-01","amount":10.00,"userId":"u1"},{"orderId":"BATCH-02","amount":20.00,"userId":"u2"}]}'` |
| **微批订单异步聚合投递** | `RabbitBatchAndPriorityController` | `POST /sample/rabbitmq/batch/orders-async` | `curl -X POST "http://localhost:8080/sample/rabbitmq/batch/orders-async" -H "Content-Type: application/json" -d '{"orders":[{"orderId":"BATCH-ASYNC-01","amount":15.00,"userId":"u1"}]}'` |
| **Quorum 队列高优先级订单** | `RabbitBatchAndPriorityController` | `POST /sample/rabbitmq/priority/order` | `curl -X POST "http://localhost:8080/sample/rabbitmq/priority/order" -H "Content-Type: application/json" -d '{"order":{"orderId":"ORD-VIP-01","amount":888.88,"userId":"vip-user"},"priority":9}'` |
| **订单超时延迟关单** | `RabbitDelayController` | `POST /sample/rabbitmq/delay/timeout-order` | `curl -X POST "http://localhost:8080/sample/rabbitmq/delay/timeout-order" -H "Content-Type: application/json" -d '{"orderId":"ORD-DELAY-01","timeoutSeconds":1800,"reason":"30分钟未支付超时取消"}'` |
| **触发致命毒丸快速进 DLQ** | `RabbitDeadLetterController` | `POST /sample/rabbitmq/dead-letter/trigger-poison` | `curl -X POST "http://localhost:8080/sample/rabbitmq/dead-letter/trigger-poison?bizKey=poison-test-01"` |
| **触发幂等静默确认 (Silent ACK)** | `RabbitDeadLetterController` | `POST /sample/rabbitmq/dead-letter/trigger-idempotent` | `curl -X POST "http://localhost:8080/sample/rabbitmq/dead-letter/trigger-idempotent?bizKey=idemp-key-1001"` |
| **死信受控重放与清洗** | `RabbitDeadLetterController` | `POST /sample/rabbitmq/dead-letter/replay` | `curl -X POST "http://localhost:8080/sample/rabbitmq/dead-letter/replay" -H "Content-Type: application/json" -d '{"dlq":"sample.biz.dlq","targetExchange":"sample.biz.direct","targetRoutingKey":"biz.work","limit":10}'` |
| **只读探查死信队列头部积压** | `RabbitDeadLetterController` | `GET /sample/rabbitmq/dead-letter/peek` | `curl -X GET "http://localhost:8080/sample/rabbitmq/dead-letter/peek?limit=5"` |
| **清空死信队列** | `RabbitDeadLetterController` | `POST /sample/rabbitmq/dead-letter/purge` | `curl -X POST "http://localhost:8080/sample/rabbitmq/dead-letter/purge"` |
| **探查死信诊断元数据** | `RabbitDeadLetterController` | `GET /sample/rabbitmq/dead-letter/audit-records` | `curl -X GET "http://localhost:8080/sample/rabbitmq/dead-letter/audit-records"` |
| **Fanout 全局广播发布** | `RabbitBroadcastController` | `POST /sample/rabbitmq/broadcast/publish` | `curl -X POST "http://localhost:8080/sample/rabbitmq/broadcast/publish" -H "Content-Type: application/json" -d '{"message":"SYSTEM_CONFIG_REFRESH"}'` |
| **探查动态匿名队列接收广播** | `RabbitBroadcastController` | `GET /sample/rabbitmq/broadcast/received` | `curl -X GET "http://localhost:8080/sample/rabbitmq/broadcast/received"` |

---

## 4. 全真自动化回归命令

执行以下 Maven 命令即可在纯内存环境中以并行加速模式完成端到端离线自验：

```bash
mvn test -pl ateng-boot-rabbitmq,ateng-boot-samples/ateng-boot-sample-rabbitmq -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-rabbitmq README](../../ateng-boot-rabbitmq/README.md)。
